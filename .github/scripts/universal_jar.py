#!/usr/bin/env python3
"""Assembles the universal jar from every loader's layer, and checks the invariants it relies on.

The universal jar is the one file a player drops into the mods folder of any supported game. Its
layout (#488):

- The root holds core's release jar once (core, gson relocated, the native, mod_api.json and the
  bundled mods), plus the layers whose mod loader reads the jar's root: the Forge layers, which
  core's GrugForgeMod starts, and 1.2.5's, whose mod_ class FML 1.2.5 loads. Their packages are
  distinct, so they sit side by side.
- Every game the jar has no layer for loads it and does nothing (#493). The root's mods.toml and
  neoforge.mods.toml, written here, declare no version range, so no Forge or NeoForge stops on
  them, and name core's Java 8 GrugForgeMod as the jar's only @Mod; the manifest's ModType makes
  FML 1.7.10 to 1.12.2 skip the jar. GAMES models each mechanism a mod loader reads the jar by.
- The Fabric-family layers are nested jars under META-INF/jars/, listed by one outer
  fabric.mod.json, because a jar holds only one fabric.mod.json and both Ornithe layers keep a
  helper in a vanilla package (net.minecraft.crafting.GrugRecipeHelper). Each nested layer depends
  on its Minecraft version and on the API its ecosystem installs beside it (OSL for Ornithe,
  StationAPI for Babric), and Fabric Loader treats a nested mod as optional, so it activates the one
  whose dependencies hold and skips the rest without failing the launch. The nested layers find
  core at the root, on the same class path.

    universal_jar.py assemble --core <core release jar> --version <v> --out <jar> <layer jar>...
    universal_jar.py check <jar>
    universal_jar.py smoke-matrix

Each layer jar is the layerJar output of a loader, named grug-<loader>.jar. `check` reads the
assembled jar and this checkout's loaders/ and mods/ directories, prints every broken invariant,
and exits 1 if there is one. It is what CI runs on the jar every production launch then uses.
`smoke-matrix` prints the games CI boots with the jar as their only mod, one per mechanism.
"""

import argparse
import io
import json
import re
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

import tomllib

REPO_ROOT = Path(__file__).resolve().parent.parent.parent

# The outer mod's id. The nested layers keep the id grug, which StationAPI turns into the namespace
# of everything grug registers, and which also lets Fabric Loader load at most one of them.
OUTER_MOD_ID = "grug-for-minecraft"
NESTED_DIR = "META-INF/jars/"
MANIFEST = "META-INF/MANIFEST.MF"
CORE_PACKAGE = "net/grug/minecraft/"
# Files every jar has a copy of its own, which a loader reads from that jar and never through the
# class path, so the root and a nested layer may both hold one.
PER_JAR_FILES = frozenset({MANIFEST, "fabric.mod.json"})
JAVA_8 = 52  # the class file major version

# The Forge family's root metadata, which assemble writes for every version at once (#493).
MODS_TOML = "META-INF/mods.toml"
NEOFORGE_MODS_TOML = "META-INF/neoforge.mods.toml"
GRUG_MOD_ID = "grug"
# Core's Java 8 @Mod class, the only one in the jar. It starts the Forge layer that core's table
# (grug_forge_layers.json) names for the running Minecraft version, and nothing on any other game.
BOOTSTRAP_MOD = "net/grug/minecraft/bootstrap/GrugForgeMod.class"
FORGE_LAYERS_TABLE = "grug_forge_layers.json"
MIXIN_PLUGIN = "net.grug.minecraft.bootstrap.GrugMixinPlugin"
FORGE_MOD = b"Lnet/minecraftforge/fml/common/Mod;"
NEOFORGE_MOD = b"Lnet/neoforged/fml/common/Mod;"
# Every annotation a Forge-family loader acts on in a class it scans, as a mod class or an event bus
# subscriber it loads. A Forge of any version that can read the class acts on it.
FML_ANNOTATIONS = (
    b"Lnet/minecraftforge/fml/common/Mod",
    b"Lnet/neoforged/fml/common/Mod",
    b"Lnet/neoforged/fml/common/EventBusSubscriber;",
    b"Lcpw/mods/fml/common/Mod",
)
FORGE_SUBSCRIBERS = (
    b"Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;",
    b"Lnet/neoforged/fml/common/EventBusSubscriber;",
    b"Lnet/neoforged/fml/common/Mod$EventBusSubscriber;",
)
# FML 1.7.10 to 1.12.2 skip a jar whose manifest's ModType does not name FML before reading any of
# its classes (CoreModManager). Their class scan would otherwise build GrugForgeMod as a mod.
MOD_TYPE = "grug"


@dataclass(frozen=True)
class Layer:
    # How the game finds the layer: "forge" reads META-INF/mods.toml at the root, "modloader" loads
    # mod_ classes at the root, and "fabric" activates a nested jar through its fabric.mod.json.
    kind: str
    # Where every class of the layer lives, as a path prefix.
    package: str
    # Classes that have to sit in a vanilla package, to reach package-private members, and so are
    # exempt from the package rule. Each one is still unique wherever it can be loaded from.
    vanilla_helpers: tuple[str, ...] = ()

    @property
    def nested(self):
        return self.kind == "fabric"


# One entry per loaders/* directory. A new loader has to be added here, or the check fails.
LAYERS = {
    "1.20.6-forge": Layer("forge", "net/grug/minecraft/forge/"),
    # Reobfuscation moves this helper from net.minecraft.src into the default package, where the
    # obfuscated vanilla classes it reaches live.
    "1.2.5-forge": Layer("modloader", "net/grug/minecraft/forge125/", ("GrugTorchFlicker.class",)),
    "a1.1.2_01-ornithe": Layer(
        "fabric", "net/grug/minecraft/ornithe/", ("net/minecraft/crafting/GrugRecipeHelper.class",)
    ),
    "b1.7.3-ornithe": Layer(
        "fabric", "net/grug/minecraft/ornithe/", ("net/minecraft/crafting/GrugRecipeHelper.class",)
    ),
    "b1.7.3-stationapi": Layer("fabric", "net/grug/minecraft/stationapi/"),
}


class AssemblyError(Exception):
    pass


def layer_name(path):
    match = re.fullmatch(r"grug-(.+)\.jar", Path(path).name)
    if not match:
        raise AssemblyError(f"{path} is not named grug-<loader>.jar")
    return match.group(1)


def read_manifest(data):
    """The main attributes of a manifest, joining continuation lines."""
    attributes = {}
    key = None
    for line in data.decode("utf-8").splitlines():
        if line.startswith(" ") and key:
            attributes[key] += line[1:]
        elif not line:
            break
        else:
            key, _, value = line.partition(": ")
            attributes[key] = value
    return attributes


def write_manifest(attributes):
    lines = []
    for key, value in attributes.items():
        line = f"{key}: {value}".encode("utf-8")
        # The jar specification limits a manifest line to 72 bytes; longer ones continue on lines
        # that start with a space.
        lines.append(line[:72])
        lines += [b" " + line[i : i + 71] for i in range(72, len(line), 71)]
    return b"\r\n".join(lines) + b"\r\n\r\n"


def class_version(data):
    """A class file's major version, which decides whether a loader's ASM can read it."""
    return int.from_bytes(data[6:8], "big")


def root_mods_toml(version):
    """mods.toml and neoforge.mods.toml for every Forge and NeoForge at once. A loaderVersion range
    or any dependency would stop the launch of every game outside it, even an optional dependency,
    which Forge range-checks whenever the mod is present. Core's GrugForgeMod picks the layer."""
    description = (
        "The grug mod loader for Minecraft. Holds one layer per supported game, "
        "and does nothing on a game it has none for."
    )
    return (
        'modLoader = "javafml"\n'
        'loaderVersion = "[0,)"\n'
        'license = "MIT"\n'
        'issueTrackerURL = "https://github.com/grug-lang/grug-for-minecraft/issues"\n'
        "\n"
        "[[mods]]\n"
        f'modId = "{GRUG_MOD_ID}"\n'
        f'version = "{version}"\n'
        'displayName = "grug mod loader"\n'
        'authors = "MyNameIsTrez"\n'
        f'description = "{description}"\n'
    ).encode()


def files_of(archive):
    return {info.filename: archive.read(info) for info in archive.infolist() if not info.is_dir()}


def outer_fabric_mod_json(version, nested):
    return {
        "schemaVersion": 1,
        "id": OUTER_MOD_ID,
        "version": version,
        "name": "grug",
        "description": "The grug mod loader for Minecraft. Holds one layer per supported game; "
        "Fabric Loader activates the one this game supports.",
        "license": "MIT",
        "contact": {
            "homepage": "https://github.com/grug-lang/grug",
            "sources": "https://github.com/grug-lang/grug-for-minecraft",
            "issues": "https://github.com/grug-lang/grug-for-minecraft/issues",
        },
        "environment": "*",
        "jars": [{"file": NESTED_DIR + f"grug-{loader}.jar"} for loader in sorted(nested)],
    }


def assemble(core, layers, version, out):
    entries = {}
    sources = {}

    def add(name, data, source):
        if name in entries:
            raise AssemblyError(f"{name} is in both {sources[name]} and {source}")
        entries[name] = data
        sources[name] = source

    with zipfile.ZipFile(core) as archive:
        for name, data in files_of(archive).items():
            if name != MANIFEST:
                add(name, data, Path(core).name)

    attributes = {"Manifest-Version": "1.0", "ModType": MOD_TYPE}
    nested = {}
    seen = set()
    for path in layers:
        loader = layer_name(path)
        if loader not in LAYERS:
            raise AssemblyError(f"{loader} has no entry in LAYERS in {Path(__file__).name}")
        if loader in seen:
            raise AssemblyError(f"{loader} is given twice")
        seen.add(loader)
        with zipfile.ZipFile(path) as archive:
            files = files_of(archive)
        if LAYERS[loader].nested != ("fabric.mod.json" in files):
            raise AssemblyError(
                f"{Path(path).name} {'lacks' if LAYERS[loader].nested else 'has'} a fabric.mod.json"
            )
        if LAYERS[loader].nested:
            nested[loader] = Path(path).read_bytes()
            continue
        for key, value in read_manifest(files.pop(MANIFEST, b"")).items():
            if key in ("Manifest-Version", "Created-By"):
                continue
            if attributes.setdefault(key, value) != value:
                raise AssemblyError(f"{loader} sets the manifest's {key} to a second value")
        for name, data in files.items():
            add(name, data, Path(path).name)

    for loader, data in nested.items():
        add(NESTED_DIR + f"grug-{loader}.jar", data, f"grug-{loader}.jar")
    add(
        "fabric.mod.json",
        (json.dumps(outer_fabric_mod_json(version, nested), indent=2) + "\n").encode(),
        Path(__file__).name,
    )
    add(MODS_TOML, root_mods_toml(version), Path(__file__).name)
    add(NEOFORGE_MODS_TOML, root_mods_toml(version), Path(__file__).name)

    # Every directory a file sits in, so a class loader can find a directory as a resource the way
    # it could in each layer jar. The manifest goes first, where JarInputStream looks for it.
    directories = {name[: i + 1] for name in entries for i, c in enumerate(name) if c == "/"}
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as archive:

        def write(name, data):
            # A fixed timestamp, so the same layers always make the same jar.
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.external_attr = ((0o40755 if name.endswith("/") else 0o100644) << 16) | (
                0x10 if name.endswith("/") else 0
            )
            info.compress_type = zipfile.ZIP_STORED if name.endswith("/") else zipfile.ZIP_DEFLATED
            archive.writestr(info, data)

        write("META-INF/", b"")
        write(MANIFEST, write_manifest(attributes))
        for name in sorted(directories - {"META-INF/"}):
            write(name, b"")
        for name in sorted(entries):
            write(name, entries[name])


# -- Reading an assembled jar -------------------------------------------------------------------


@dataclass
class Jar:
    name: str
    files: dict
    duplicates: list = field(default_factory=list)

    @classmethod
    def read(cls, name, data):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            names = [info.filename for info in archive.infolist()]
            files = files_of(archive)
        seen = set()
        duplicates = sorted({n for n in names if n in seen or seen.add(n)})
        return cls(name, files, duplicates)

    def classes(self):
        return [name for name in self.files if name.endswith(".class")]

    def json(self, name, problems):
        try:
            return json.loads(self.files[name])
        except (KeyError, ValueError) as error:
            problems.append(f"{self.name}: {name} does not parse: {error!r}")
            return None


@dataclass
class Universal:
    root: Jar
    nested: dict  # loader -> Jar
    problems: list

    @classmethod
    def read(cls, path):
        root = Jar.read(Path(path).name, Path(path).read_bytes())
        nested, problems = {}, []
        for name, data in root.files.items():
            if not name.startswith(NESTED_DIR) or not name.endswith(".jar"):
                continue
            match = re.fullmatch(re.escape(NESTED_DIR) + r"grug-(.+)\.jar", name)
            if not match or match.group(1) not in LAYERS or not LAYERS[match.group(1)].nested:
                problems.append(f"{name} is not a nested layer of any loader in LAYERS")
                continue
            nested[match.group(1)] = Jar.read(name, data)
        return cls(root, nested, problems)

    def scopes(self):
        """Every set of files one game can load at once: the root plus at most one nested layer."""
        if not self.nested:
            return [("the root", self.root.files)]
        return [
            (f"the root and {jar.name}", {**self.root.files, **jar.files})
            for jar in self.nested.values()
        ]


def root_class_owner(name):
    """The loader whose layer a class at the root belongs to, "core", or None for neither."""
    for loader, layer in LAYERS.items():
        if name in layer.vanilla_helpers and not layer.nested:
            return loader
    owners = [loader for loader, layer in LAYERS.items() if name.startswith(layer.package)]
    if owners:
        return owners[0]
    return "core" if name.startswith(CORE_PACKAGE) else None


def root_layers(jar):
    return {
        owner
        for owner in map(root_class_owner, jar.root.classes())
        if owner not in (None, "core") and not LAYERS[owner].nested
    }


# -- The checks ---------------------------------------------------------------------------------


def check_duplicates(jar):
    problems = [f"{jar.root.name} holds {name} more than once" for name in jar.root.duplicates]
    for nested in jar.nested.values():
        problems += [f"{nested.name} holds {name} more than once" for name in nested.duplicates]
    # A nested layer shares the class path with the root, so a path in both resolves to whichever
    # the class loader finds first: a class would be shadowed, and a mixin config of the same name
    # would hand one layer the other's mixins.
    for nested in jar.nested.values():
        for name in sorted((set(jar.root.files) & set(nested.files)) - PER_JAR_FILES):
            problems.append(f"{name} is both at the root and in {nested.name}")
    return problems


def check_packages(jar):
    problems = []
    for name in sorted(jar.root.classes()):
        owner = root_class_owner(name)
        if owner is None:
            problems.append(f"{name} at the root is neither core's nor in a root layer's package")
        elif owner != "core" and LAYERS[owner].nested:
            problems.append(f"{name} at the root is in the package of {owner}, a nested layer")
    for loader, nested in jar.nested.items():
        layer = LAYERS[loader]
        for name in sorted(nested.classes()):
            if not name.startswith(layer.package) and name not in layer.vanilla_helpers:
                problems.append(f"{name} in {nested.name} is outside {layer.package}")
    return problems


def check_layers(jar, loaders):
    present = set(jar.nested) | root_layers(jar)
    problems = [f"loaders/{loader} has no layer in the jar" for loader in sorted(loaders - present)]
    problems += [
        f"the jar has a layer for {loader}, which is not in loaders/"
        for loader in sorted(present - loaders)
    ]
    problems += [
        f"loaders/{loader} has no entry in LAYERS" for loader in sorted(loaders - set(LAYERS))
    ]
    return problems


def check_payload(jar, mods):
    # Each appears once at the root, which the duplicate check guarantees, and in no nested layer.
    singles = ("mod_api.json", "grug_bundled_mods.txt")
    problems = [
        f"{name} is missing from the root" for name in singles if name not in jar.root.files
    ]
    if not any(name.startswith("natives/") for name in jar.root.files):
        problems.append("the root holds no native library under natives/")
    for nested in jar.nested.values():
        for name in sorted(nested.files):
            if name in singles or name.startswith(("natives/", "mods/")):
                problems.append(
                    f"{name} is in {nested.name}, while the root holds it for every layer"
                )
            elif name.startswith(CORE_PACKAGE) and root_class_owner(name) == "core":
                problems.append(
                    f"{name} is core's and is in {nested.name}; core belongs at the root once"
                )
            elif name.startswith(NESTED_DIR) and "grug-core" in name:
                problems.append(f"{nested.name} nests {name}; core belongs at the root once")

    bundled = {name.split("/")[1] for name in jar.root.files if name.startswith("mods/")}
    problems += [f"mods/{mod} is not bundled in the jar" for mod in sorted(mods - bundled)]
    problems += [
        f"the jar bundles mods/{mod}, which is not in mods/" for mod in sorted(bundled - mods)
    ]
    index = jar.root.files.get("grug_bundled_mods.txt", b"").decode().splitlines()
    listed = {"mods/" + line for line in index if line}
    actual = {name for name in jar.root.files if name.startswith("mods/")}
    problems += [
        f"grug_bundled_mods.txt lists {name}, which the jar lacks"
        for name in sorted(listed - actual)
    ]
    problems += [f"{name} is not in grug_bundled_mods.txt" for name in sorted(actual - listed)]
    return problems


def check_mixin_config(config, owner, files, problems):
    if config not in files:
        problems.append(f"{owner} names mixin config {config}, which is missing")
        return
    try:
        data = json.loads(files[config])
    except ValueError as error:
        problems.append(f"{config} in {owner} does not parse: {error!r}")
        return
    package = data.get("package", "").replace(".", "/")
    # A Forge layer's config lists its mixins under "grug", for GrugMixinPlugin to hand out.
    grug = data.get("grug", {})
    for side in ("mixins", "client", "server"):
        for mixin in data.get(side, []) + grug.get(side, []):
            path = f"{package}/{mixin.replace('.', '/')}.class"
            if path not in files:
                problems.append(f"{config} in {owner} names mixin {path}, which is missing")
    for key in ("refmap", "plugin"):
        if key not in data:
            continue
        path = data[key] if key == "refmap" else data[key].replace(".", "/") + ".class"
        if path not in files:
            problems.append(f"{config} in {owner} names {key} {path}, which is missing")


def manifest_mixin_configs(root):
    configs = read_manifest(root.files.get(MANIFEST, b"")).get("MixinConfigs", "")
    return [c for c in (c.strip() for c in configs.split(",")) if c]


def gradle_property(path, key):
    try:
        lines = path.read_text().splitlines()
    except OSError:
        return None
    for line in lines:
        name, _, value = line.partition("=")
        if name.strip() == key:
            return value.strip()
    return None


def check_forge_family(jar, repo):
    """The root metadata every Forge and NeoForge reads, and the table core's GrugForgeMod and
    GrugMixinPlugin start a Forge layer from (#493)."""
    problems = []
    root = jar.root
    for name in (MODS_TOML, NEOFORGE_MODS_TOML):
        try:
            toml = tomllib.loads(root.files[name].decode())
        except (KeyError, ValueError) as error:
            problems.append(f"{name} does not parse: {error!r}")
            continue
        mods = [mod.get("modId") for mod in toml.get("mods", [])]
        if mods != [GRUG_MOD_ID]:
            problems.append(f"{name} declares the mods {mods}, rather than only {GRUG_MOD_ID}")
        if toml.get("dependencies"):
            problems.append(
                f"{name} declares dependencies, whose ranges stop the launch of every game "
                "outside them, even an optional one's"
            )
        if toml.get("loaderVersion") != "[0,)":
            problems.append(
                f"{name} asks for javafml {toml.get('loaderVersion')!r}, rather than every "
                'version, "[0,)"'
            )

    for descriptor in (FORGE_MOD, NEOFORGE_MOD):
        owners = [n for n in root.classes() if descriptor in root.files[n]]
        if owners != [BOOTSTRAP_MOD]:
            problems.append(
                f"{descriptor.decode()} is on {owners}, rather than only on {BOOTSTRAP_MOD}"
            )
    for name in root.classes():
        if name != BOOTSTRAP_MOD and any(a in root.files[name] for a in FML_ANNOTATIONS):
            problems.append(
                f"{name} carries an FML annotation, which every Forge that can read it acts on, "
                "whatever version the class targets"
            )
    if BOOTSTRAP_MOD in root.files and class_version(root.files[BOOTSTRAP_MOD]) > JAVA_8:
        problems.append(
            f"{BOOTSTRAP_MOD} is newer than Java 8, which Forge 1.13 to 1.16 cannot read"
        )

    mod_types = read_manifest(root.files.get(MANIFEST, b"")).get("ModType", "FML").split(",")
    if "FML" in mod_types:
        problems.append(
            "the manifest's ModType does not exclude FML, so FML 1.7.10 to 1.12.2 read every class"
        )

    try:
        table = json.loads(root.files[FORGE_LAYERS_TABLE])
    except (KeyError, ValueError) as error:
        return problems + [f"{FORGE_LAYERS_TABLE} does not parse: {error!r}"]
    forge = {loader for loader, layer in LAYERS.items() if layer.kind == "forge"}
    for loader in sorted((root_layers(jar) & forge) - set(table)):
        problems.append(
            f"{loader} has no entry in {FORGE_LAYERS_TABLE}, so GrugForgeMod never starts it"
        )
    configs = manifest_mixin_configs(root)
    for loader, entry in sorted(table.items()):
        if loader not in forge:
            problems.append(f"{FORGE_LAYERS_TABLE} names {loader}, which is no Forge layer")
            continue
        wanted = gradle_property(
            repo / "loaders" / loader / "gradle.properties", "minecraft_version"
        )
        if entry.get("minecraft") != wanted:
            problems.append(
                f"{FORGE_LAYERS_TABLE} starts {loader} on Minecraft {entry.get('minecraft')}, "
                f"but its gradle.properties targets {wanted}"
            )
        entrypoint = entry.get("entrypoint", "").replace(".", "/") + ".class"
        if entrypoint not in root.files:
            problems.append(
                f"{FORGE_LAYERS_TABLE} starts {loader} at {entrypoint}, which is missing"
            )
        mixins = entry.get("mixins")
        if mixins not in configs:
            problems.append(
                f"{loader}'s mixin config {mixins} is not in the manifest's MixinConfigs"
            )
        if mixins not in root.files:
            continue  # check_mixin_config already reports a config the manifest names
        try:
            config = json.loads(root.files[mixins])
        except ValueError:
            continue  # and one that does not parse
        if config.get("plugin") != MIXIN_PLUGIN:
            problems.append(f"{mixins} is not handed out by {MIXIN_PLUGIN}")
        for side in ("mixins", "client", "server"):
            if config.get(side):
                problems.append(
                    f"{mixins} lists {side} mixins itself, which every game that reads the "
                    "manifest parses; list them under grug"
                )
    return problems


def check_metadata(jar):
    problems = []
    root = jar.root
    for config in manifest_mixin_configs(root):
        check_mixin_config(config, "the manifest", root.files, problems)

    modloader = [loader for loader, layer in LAYERS.items() if layer.kind == "modloader"]
    if root_layers(jar) & set(modloader):
        mod_classes = [n for n in root.classes() if n.rsplit("/", 1)[-1].startswith("mod_")]
        if len(mod_classes) != 1:
            problems.append(
                f"the root holds {len(mod_classes)} mod_ classes for ModLoader, not one"
            )

    outer = root.json("fabric.mod.json", problems)
    if outer is not None:
        if outer.get("schemaVersion") != 1 or outer.get("id") != OUTER_MOD_ID:
            problems.append(f"the outer fabric.mod.json is not schema 1 with id {OUTER_MOD_ID}")
        for key in ("entrypoints", "mixins", "depends"):
            if outer.get(key):
                problems.append(f"the outer fabric.mod.json declares {key}; it has to load inert")
        listed = {entry["file"] for entry in outer.get("jars", [])}
        actual = {NESTED_DIR + Path(n.name).name for n in jar.nested.values()}
        problems += [
            f"the outer fabric.mod.json lists {n}, which is missing"
            for n in sorted(listed - actual)
        ]
        problems += [
            f"{n} is not listed in the outer fabric.mod.json" for n in sorted(actual - listed)
        ]

    for loader, nested in jar.nested.items():
        metadata = nested.json("fabric.mod.json", problems)
        if metadata is None:
            continue
        files = {**root.files, **nested.files}
        for stage, entrypoints in metadata.get("entrypoints", {}).items():
            for entrypoint in entrypoints:
                value = entrypoint["value"] if isinstance(entrypoint, dict) else entrypoint
                path = value.split("::")[0].replace(".", "/") + ".class"
                if path not in files:
                    problems.append(
                        f"{nested.name} has {stage} entrypoint {value}, which is missing"
                    )
        for config in metadata.get("mixins", []):
            config = config["config"] if isinstance(config, dict) else config
            check_mixin_config(config, nested.name, files, problems)
        if "accessWidener" in metadata and metadata["accessWidener"] not in nested.files:
            problems.append(
                f"{nested.name} names access widener {metadata['accessWidener']}, which is missing"
            )
        for entry in metadata.get("jars", []):
            if entry["file"] not in nested.files:
                problems.append(f"{nested.name} lists nested jar {entry['file']}, which is missing")
    return problems


# -- Which layer a game activates ---------------------------------------------------------------


def parse_semver(text):
    """A version as Fabric Loader compares it: numbers, then pre-release identifiers, with build
    metadata ignored and a release ranked above all of its pre-releases."""
    core, _, pre = text.split("+")[0].partition("-")
    numbers = tuple(int(part) for part in core.split("."))
    identifiers = (
        tuple((0, int(p), "") if p.isdigit() else (1, 0, p) for p in pre.split(".")) if pre else ()
    )
    return numbers, identifiers


def compare_semver(a, b):
    (numbers_a, pre_a), (numbers_b, pre_b) = parse_semver(a), parse_semver(b)
    width = max(len(numbers_a), len(numbers_b))
    numbers_a += (0,) * (width - len(numbers_a))
    numbers_b += (0,) * (width - len(numbers_b))
    if numbers_a != numbers_b:
        return -1 if numbers_a < numbers_b else 1
    if pre_a == pre_b:
        return 0
    if not pre_a or not pre_b:
        return 1 if not pre_a else -1
    return -1 if pre_a < pre_b else 1


def fabric_predicate_matches(predicate, version):
    """Whether a fabric.mod.json version predicate accepts a version. Only the operators grug's
    metadata uses are understood, and anything else raises, so new syntax extends this rather than
    being misread."""
    for term in predicate.split():
        if term == "*":
            continue
        operator = re.match(r"(>=|<=|>|<|=)?", term).group(1) or "="
        wanted = term[len(operator) if term[0] in "<>=" else 0 :]
        if not re.fullmatch(r"\d+(\.\d+)*(-[0-9A-Za-z.]+)?(\+[0-9A-Za-z.]+)?", wanted):
            raise ValueError(f"cannot evaluate the version predicate {predicate!r}")
        order = compare_semver(version, wanted)
        if not {
            ">=": order >= 0,
            "<=": order <= 0,
            ">": order > 0,
            "<": order < 0,
            "=": order == 0,
        }[operator]:
            return False
    return True


def maven_range_matches(spec, version):
    """Whether a mods.toml versionRange such as [1.20.6,1.21) accepts a version."""
    match = re.fullmatch(r"([\[(])([^,\])]*)(?:,([^\])]*))?([\])])", spec.replace(" ", ""))
    if not match:
        raise ValueError(f"cannot evaluate the version range {spec!r}")
    opening, low, high, closing = match.groups()
    if high is None:  # [1.2.3] means exactly that version
        return compare_semver(version, low) == 0
    if low and compare_semver(version, low) < (0 if opening == "[" else 1):
        return False
    return not high or compare_semver(version, high) <= (0 if closing == "]" else -1)


@dataclass(frozen=True)
class Game:
    name: str
    # How its mod loader reads a jar in mods/, one of MECHANISMS.
    kind: str
    minecraft: str
    # The other mods present, by id, with their versions. Fabric's builtin fabricloader is one.
    mods: dict
    # The layer this game should activate, or None for a game grug does not support.
    expect: str | None
    # The newest class file major version the loader's class scan reads (its ASM's), for the
    # mechanisms that scan classes.
    asm: int = 0
    # The launch recipe of .github/scripts/smoke-launch.sh that boots this game on CI with the jar
    # as its only mod, for the games the smoke jobs cover.
    smoke: str | None = None


# How each mod loader reads a jar in mods/, as read from its own code (#493).
MECHANISMS = {
    "fabric": "Fabric Loader: the outer fabric.mod.json, then each nested jar whose depends hold",
    "forge": "Forge 1.13 on and NeoForge 1.20.1: mods.toml, then the classes its ASM reads",
    "neoforge-modstoml": "NeoForge 20.2 to 20.4: mods.toml with NeoForge's @Mod",
    "neoforge": "NeoForge 20.5 on: only neoforge.mods.toml, warning about any other mod jar",
    "fml-asm5": "FML 1.7.10 to 1.12.2: the manifest's ModType, then every class through ASM 5",
    "fml-asm4": "FML 1.3.2 to 1.6.4: every class through ASM 4, until one it cannot read",
    "fml125": "FML 1.2.5: mod_ classes anywhere in the jar",
    "modloader": "Risugami's ModLoader: mod_ classes at the jar's root",
}
# Mechanisms no smoke job can cover, because no game of theirs lacks a layer.
SMOKE_EXEMPT = {"fml125": "FML 1.2.5 only exists for Minecraft 1.2.5, which 1.2.5-forge supports"}

# The OSL modules the Ornithe layers use, as the OSL 0.20.3 bundle a player installs provides them.
OSL = {
    "osl": "0.20.3",
    "osl-blocks": "0.1.1",
    "osl-core": "0.9.0",
    "osl-entrypoints": "0.6.1",
    "osl-keybinds": "0.3.0",
    "osl-lifecycle-events": "0.6.1",
    "osl-resource-loader": "0.7.4",
}
ORNITHE = {"fabricloader": "0.19.5", **OSL}
BABRIC = {"fabricloader": "0.17.3"}
STATIONAPI = {**BABRIC, "stationapi": "2.0.0-alpha.6.2"}

# The class file major version each scanner reads up to, from the ASM its installer ships: ASM 4
# reads Java 7 (51), ASM 5 Java 8 (52), ASM 6.2 Java 11 (55), ASM 9.5 Java 21 (65), and ASM 9.6 on
# Java 22 (66) and newer.
GAMES = [
    Game("Ornithe a1.1.2_01 with OSL", "fabric", "1.0.0-alpha.1.2.1", ORNITHE, "a1.1.2_01-ornithe"),
    Game("Ornithe b1.7.3 with OSL", "fabric", "1.0.0-beta.7.3", ORNITHE, "b1.7.3-ornithe"),
    Game(
        "Babric b1.7.3 with StationAPI", "fabric", "1.0.0-beta.7.3", STATIONAPI, "b1.7.3-stationapi"
    ),
    Game("Forge 1.20.6", "forge", "1.20.6", {"forge": "50.1.0"}, "1.20.6-forge", asm=69),
    Game("Forge 1.2.5", "fml125", "1.2.5", {}, "1.2.5-forge"),
    # Games grug does not support, where the jar has to load and do nothing.
    Game(
        "Babric b1.7.3 without StationAPI",
        "fabric",
        "1.0.0-beta.7.3",
        BABRIC,
        None,
        smoke="babric-b1.7.3",
    ),
    # Babric on a loader new enough for OSL, so only OSL's absence keeps the Ornithe layer out.
    Game(
        "Babric b1.7.3 on Fabric Loader 0.19.5",
        "fabric",
        "1.0.0-beta.7.3",
        {"fabricloader": "0.19.5"},
        None,
    ),
    Game(
        "Ornithe a1.1.2_01 without OSL",
        "fabric",
        "1.0.0-alpha.1.2.1",
        {"fabricloader": "0.19.5"},
        None,
    ),
    Game(
        "Ornithe b1.7.3 without OSL", "fabric", "1.0.0-beta.7.3", {"fabricloader": "0.19.5"}, None
    ),
    Game("Ornithe a1.2.6 with OSL", "fabric", "1.0.0-alpha.2.6", ORNITHE, None),
    Game("Ornithe b1.8.1 with OSL", "fabric", "1.0.0-beta.8.1", ORNITHE, None),
    Game("Ornithe 1.12.2 with OSL", "fabric", "1.12.2", ORNITHE, None),
    Game(
        "Fabric 1.20.6",
        "fabric",
        "1.20.6",
        {"fabricloader": "0.16.14", "fabric-api": "0.100.8"},
        None,
    ),
    Game("Fabric 1.21.1", "fabric", "1.21.1", {"fabricloader": "0.16.14"}, None),
    Game("Forge 1.13.2", "forge", "1.13.2", {"forge": "25.0.223"}, None, asm=55),
    # The oldest Forge with Mixin, on Java 8.
    Game(
        "Forge 1.16.5", "forge", "1.16.5", {"forge": "36.2.34"}, None, asm=66, smoke="forge-1.16.5"
    ),
    Game("Forge 1.20.1", "forge", "1.20.1", {"forge": "47.4.10"}, None, asm=69),
    Game("NeoForge 1.20.1", "forge", "1.20.1", {"forge": "47.1.106"}, None, asm=65),
    Game("Forge 1.20.4", "forge", "1.20.4", {"forge": "49.2.0"}, None, asm=69),
    Game(
        "Forge 1.21.1", "forge", "1.21.1", {"forge": "52.1.0"}, None, asm=69, smoke="forge-1.21.1"
    ),
    Game(
        "NeoForge 20.4",
        "neoforge-modstoml",
        "1.20.4",
        {"neoforge": "20.4.253"},
        None,
        asm=69,
        smoke="neoforge-20.4",
    ),
    Game(
        "NeoForge 21.1",
        "neoforge",
        "1.21.1",
        {"neoforge": "21.1.264"},
        None,
        asm=69,
        smoke="neoforge-21.1",
    ),
    Game(
        "Forge 1.12.2",
        "fml-asm5",
        "1.12.2",
        {"forge": "14.23.5.2859"},
        None,
        asm=52,
        smoke="forge-1.12.2",
    ),
    Game("Forge 1.7.10", "fml-asm5", "1.7.10", {"forge": "10.13.4.1614"}, None, asm=52),
    Game(
        "Forge 1.6.4",
        "fml-asm4",
        "1.6.4",
        {"forge": "9.11.1.1345"},
        None,
        asm=51,
        smoke="forge-1.6.4",
    ),
    Game("Forge 1.4.7", "fml-asm4", "1.4.7", {"forge": "6.6.2.534"}, None, asm=51),
    Game("ModLoader b1.7.3", "modloader", "1.0.0-beta.7.3", {}, None, smoke="modloader-b1.7.3"),
    Game("ModLoader 1.2.5 without Forge", "modloader", "1.2.5", {}, None),
]

# Games where the jar is known not to behave as GAMES expects, each with the issue that tracks it.
# The check requires the outcome to stay exactly as recorded, so a fix fails it until its entry is
# removed, and a new gap cannot hide behind an old one.
KNOWN_GAPS = {}


def forge_family(jar, game, toml_name, annotation, starts_layers):
    """What a Forge or NeoForge does with the jar once it has found toml_name at its root."""
    root = jar.root
    mods_toml = tomllib.loads(root.files[toml_name].decode())
    present = {"minecraft": game.minecraft, **game.mods}
    for dependencies in mods_toml.get("dependencies", {}).values():
        for dependency in dependencies:
            mandatory = dependency.get(
                "mandatory", dependency.get("type", "required") == "required"
            )
            version = present.get(dependency["modId"])
            if version is None:
                if mandatory:
                    return "breaks the launch"
            # Forge 1.16.5 on also range-checks an optional dependency that is present.
            elif not maven_range_matches(dependency.get("versionRange", "[0,)"), version):
                return "breaks the launch"

    readable = [n for n in root.classes() if class_version(root.files[n]) <= game.asm]
    for mod in mods_toml.get("mods", []):
        mod_id = mod["modId"].encode()
        classes = [n for n in readable if annotation in root.files[n] and mod_id in root.files[n]]
        if len(classes) != 1:  # none, or two mod classes for one id
            return "breaks the launch"
    subscribers = [n for n in readable if any(s in root.files[n] for s in FORGE_SUBSCRIBERS)]
    if subscribers:
        return "loads " + ", ".join(subscribers)
    if not starts_layers:
        return []
    # GrugForgeLayers reads the game from FMLLoader.versionInfo(), which Forge has from 1.17 on.
    if compare_semver(game.minecraft, "1.17") < 0:
        return []
    table = json.loads(root.files[FORGE_LAYERS_TABLE])
    return sorted(loader for loader, entry in table.items() if entry["minecraft"] == game.minecraft)


def activate(jar, game):
    """The layers a game activates, or a description of what it does instead when that is not
    loading and doing nothing: "breaks the launch", or which of the jar's classes it loads."""
    root = jar.root
    if game.kind == "modloader":
        mod_classes = [n for n in root.classes() if re.fullmatch(r"mod_[^/]*\.class", n)]
        return "loads " + ", ".join(mod_classes) if mod_classes else []
    if game.kind == "fml125":
        mod_classes = [n for n in root.classes() if re.fullmatch(r"(.+/|)mod_[^\s$]+\.class", n)]
        stray = [n for n in mod_classes if root_class_owner(n) in (None, "core")]
        if stray:
            return "loads " + ", ".join(stray)
        return sorted({root_class_owner(n) for n in mod_classes})
    if game.kind == "fml-asm5":
        mod_types = read_manifest(root.files.get(MANIFEST, b"")).get("ModType", "FML")
        return [] if "FML" not in mod_types.split(",") else "reads every class"
    if game.kind == "fml-asm4":
        # The first class it cannot read makes it ignore the jar, and it reads them in jar order.
        first = next(iter(root.classes()), None)
        if first is None or class_version(root.files[first]) > game.asm:
            return []
        return f"reads {first}"
    if game.kind == "forge":
        if MODS_TOML not in root.files:
            return "breaks the launch"
        return forge_family(jar, game, MODS_TOML, FORGE_MOD, starts_layers=True)
    if game.kind == "neoforge-modstoml":
        if MODS_TOML not in root.files:
            return "breaks the launch"
        return forge_family(jar, game, MODS_TOML, NEOFORGE_MOD, starts_layers=False)
    if game.kind == "neoforge":
        if NEOFORGE_MODS_TOML in root.files:
            return forge_family(jar, game, NEOFORGE_MODS_TOML, NEOFORGE_MOD, starts_layers=False)
        if MODS_TOML in root.files or "fabric.mod.json" in root.files:
            return "shows a warning screen"
        return []

    present = {"minecraft": game.minecraft, **game.mods}

    def satisfied(metadata):
        for mod_id, predicates in metadata.get("depends", {}).items():
            versions = predicates if isinstance(predicates, list) else [predicates]
            if mod_id not in present or not any(
                fabric_predicate_matches(p, present[mod_id]) for p in versions
            ):
                return False
        for mod_id, predicates in metadata.get("breaks", {}).items():
            versions = predicates if isinstance(predicates, list) else [predicates]
            if mod_id in present and any(
                fabric_predicate_matches(p, present[mod_id]) for p in versions
            ):
                return False
        return True

    outer = json.loads(root.files["fabric.mod.json"])
    if not satisfied(outer):  # the outer mod is in mods/, so an unmet dependency is an error
        return "breaks the launch"
    return sorted(
        loader
        for loader, nested in jar.nested.items()
        if satisfied(json.loads(nested.files["fabric.mod.json"]))
    )


def describe(outcome):
    if isinstance(outcome, str):
        return outcome
    return "activates " + " and ".join(outcome) if outcome else "activates no layer"


def check_smoke_games(games=GAMES):
    """The smoke jobs boot one game per mechanism that the jar does not support (#493). A game that
    gains a layer leaves them for the production jobs, and its mechanism needs another game."""
    problems = []
    for game in games:
        if game.kind not in MECHANISMS:
            problems.append(f"{game.name} uses the mechanism {game.kind}, which MECHANISMS lacks")
        if game.smoke and game.expect:
            problems.append(
                f"{game.name} is a smoke game, but the jar supports it; its production job covers "
                f"it, so move the smoke recipe {game.smoke} to a {game.kind} game without a layer"
            )
    covered = {game.kind for game in games if game.smoke and not game.expect}
    for kind in sorted(set(MECHANISMS) - set(SMOKE_EXEMPT) - covered):
        problems.append(f"no smoke game covers the {kind} mechanism ({MECHANISMS[kind]})")
    recipes = [game.smoke for game in games if game.smoke]
    for recipe in sorted({r for r in recipes if recipes.count(r) > 1}):
        problems.append(f"the smoke recipe {recipe} is on more than one game")
    return problems


def smoke_matrix(games=GAMES):
    """The smoke jobs' GitHub Actions matrix, one entry per smoke game."""
    return [{"game": game.name, "recipe": game.smoke} for game in games if game.smoke]


def check_dispatcher(jar, games=GAMES, known_gaps=KNOWN_GAPS):
    problems = check_smoke_games(games)
    for game in games:
        try:
            outcome = activate(jar, game)
        except (KeyError, ValueError) as error:
            problems.append(f"{game.name}: cannot evaluate the jar's metadata: {error!r}")
            continue
        expected = describe([game.expect] if game.expect else [])
        actual = describe(outcome)
        if game.name in known_gaps:
            gap, issue = known_gaps[game.name]
            if actual != gap:
                problems.append(
                    f"{game.name} now {actual} rather than {gap}; update KNOWN_GAPS (#{issue})"
                )
        elif actual != expected:
            problems.append(
                f"{game.name} {actual}, but should {expected.replace('activates', 'activate')}"
            )
    return problems


def check(path, repo=REPO_ROOT):
    jar = Universal.read(path)
    loaders = {p.name for p in (repo / "loaders").iterdir() if p.is_dir()}
    mods = {p.name for p in (repo / "mods").iterdir() if p.is_dir()}
    problems = list(jar.problems)
    for run in (
        check_duplicates,
        check_packages,
        lambda j: check_layers(j, loaders),
        lambda j: check_payload(j, mods),
        check_metadata,
        lambda j: check_forge_family(j, repo),
        check_dispatcher,
    ):
        problems += run(jar)
    return problems


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    commands = parser.add_subparsers(dest="command", required=True)
    assembling = commands.add_parser("assemble", help="build the universal jar from the layers")
    assembling.add_argument("--core", required=True, help="core's release jar")
    assembling.add_argument("--version", required=True, help="the version the outer mod reports")
    assembling.add_argument("--out", required=True, help="where to write the universal jar")
    assembling.add_argument("layers", nargs="+", help="each loader's layerJar, grug-<loader>.jar")
    checking = commands.add_parser("check", help="check an assembled jar's invariants")
    checking.add_argument("jar")
    checking.add_argument(
        "--repo", default=REPO_ROOT, type=Path, help="the checkout to compare with"
    )
    commands.add_parser("smoke-matrix", help="print the smoke jobs' matrix as JSON")
    args = parser.parse_args(argv)

    if args.command == "smoke-matrix":
        print(json.dumps(smoke_matrix()))
        return 0

    if args.command == "assemble":
        try:
            assemble(args.core, args.layers, args.version, args.out)
        except AssemblyError as error:
            print(f"==> ERROR: {error}", file=sys.stderr)
            return 1
        print(f"==> Wrote {args.out}")
        return 0

    problems = check(Path(args.jar), args.repo)
    for problem in problems:
        print(f"==> ERROR: {problem}", file=sys.stderr)
    if problems:
        return 1
    print(f"==> {args.jar} holds every invariant")
    return 0


if __name__ == "__main__":
    sys.exit(main())
