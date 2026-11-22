#!/usr/bin/env python3
"""Combine the per-runner JaCoCo artifacts that CI uploads into one coverage report.

The core job and every loader job upload:

    coverage-exec-<runner>/<name>.exec
    coverage-classes-<runner>/...

and the multiplayer job uploads its dedicated server's exec data apart, as
server-exec-<loader>/<name>.exec, which is appended to that loader's own exec list (#1027): the
runner's classes are then reported once, over the client run and the server run together. An Ornithe
runner's classes only the server ran come from the layer-<loader> artifact instead, which holds the
jar the server loads, and are reported as a second class set of that runner (#169).

A single combined JaCoCo report is not possible, because different loaders compile different classes
with the same fully-qualified names (net.grug.minecraft.ornithe.OrnitheAdapter exists in both Ornithe
loaders, with different bytecode), and JaCoCo refuses to analyse two different classes with the same
name. So we generate one report per runner, and then sum their CSVs into one combined CSV that the
coverage gate reads.

Core is special: every loader run executes core's classes, so core's report unions every runner's
exec data. Each loader's report only uses its own exec and its own class files. Classes listed in
EXCLUDED_CLASSES are dropped from every report.

A loader's exec files are not the whole game run. GrugCoverage cuts the run into segments and writes
the boot before the first test (000-boot.exec) and the segments of mods/coverage/'s tests
(NNN-coverage.exec, one per stretch of that mod's tests, so a repeated suite has several) as .exec
files, and every other mod's segments as .dropped files. The gate counts the unit tests, the boot
and mods/coverage/ and no other mod's tests (#626), with one exception: a class initializer counts
wherever it ran, because it runs once per JVM, in the segment of whichever mod's test first used its
class, and a dropped segment's reset would otherwise leave it uncovered however well mods/coverage/
tests the class. rescue_class_initializers compares a report of the .exec files with one of the
.exec and .dropped files together and counts what only the second shows in a <clinit> method, in the
CSV the gate reads and in the XML the per-line report comes from.

The gate also counts, when a pull request did not rerun them, the coverage a coverage test recorded
on an earlier run (#786): such a test's recorded coverage is handed to the reports as one
--carry-over exec per runner the test ran on. That runner's report counts it like the .exec files of
its tests that ran, which is what makes a covered loader class count, and core's report counts it
too, since core unions every runner's exec data. A pull request carries over only the tests a static
proof shows the diff cannot affect, and the gate still fails if any counted line or branch has no
cover, from the run or from the carried-over tests.

A carried-over test brings the other mods' recorded coverage of its base run with it, as one
--carry-over-dropped exec per runner (#897): it is handed to that runner's and core's dropped data,
never to the counted one, so rescue_class_initializers counts the class initializers that ran in
another mod's segment and nothing else, which is what a run counts of its own dropped segments. The
base run's map records those segments for exactly this reason: a pull request that carries every
coverage test over runs no test at all, so without them the initializers that only another mod's test
made run would be missed by every run of it.
"""

from __future__ import annotations

import argparse
import csv
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

CORE = "core"

# The artifact name a dedicated server's exec data comes in, without the loader it belongs to. The
# loader jobs' own names start with coverage-, which the map build reads, so the server's data
# deliberately does not (#1027).
SERVER_EXEC = "server-exec-"

# These loaders compile shared/ornithe as an extra source directory.
ORNITHE_LOADERS = {"a1.1.2_01-ornithe", "b1.7.3-ornithe"}

# The artifact the layer job uploads: the loader's remapped jar, which is what an Ornithe dedicated
# server loads as its mod, so its class bytes are the ones the server's exec data was recorded
# against (the client run's staged classes are the dev build's, with other names).
LAYER = "layer-"

# The loader classes only a dedicated server runs (#169's twenty-one), by runner. Each carries a
# class-level @GrugGenerated or sits in EXCLUDED_CLASSES, so no report shows it; measure_server_only
# reads what the gate would count for it if that exclusion came off, and prints it (#1600).
SERVER_ONLY = {
    "1.20.6-forge": ["ServerForgeAdapter", "GrugServerRun", "GrugControlForge"],
    "1.2.5-forge": ["Grug125ServerAdapter", "GrugServerRun", "GrugControlServer125"],
    "a1.1.2_01-ornithe": [
        "ServerOrnitheAdapter",
        "GrugServerRun",
        "GrugControlAlpha",
        "ServerGrugModLoader",
        "GrugPlayerManagerAccess",
    ],
    "b1.7.3-ornithe": [
        "ServerOrnitheAdapter",
        "GrugServerRun",
        "GrugControlBeta",
        "ServerGrugModLoader",
    ],
    "b1.7.3-stationapi": [
        "ServerStationApiAdapter",
        "GrugServerRun",
        "GrugControlStation",
        "ServerAdapterChoice",
        "ServerInitListener",
        "ServerChatReports",
    ],
}

# JaCoCo drops a class whose annotation's name contains "Generated". The descriptor of
# net.grug.minecraft.grug.GrugGenerated is patched to a name of the same length without it, which
# keeps every constant pool offset and so needs no class file writer.
GENERATED_DESCRIPTOR = b"GrugGenerated;"
UNMASKED_DESCRIPTOR = b"GrugGeneratxd;"

# Classes deliberately left out of the combined report, kept in one place so the exclusions stay
# reviewable:
# - GenericHostFunctions is mechanically derived from one template and parameterized only by the
#   type tables in core/generate.py, so its per-permutation coverage adds nothing over testing those
#   tables directly (see core/test_generate.py). ExportFns is deliberately not excluded: its
#   remaining wrappers map to distinct callbacks.
# - Mixin classes can never be measured: Mixin copies a mixin's methods into its target without
#   defining the mixin class, so JaCoCo never instruments them. The logic they used to hold lives in
#   measured net.grug.* helpers instead.
# - ServerGrugModLoader runs only on a dedicated server. That server's exec data reaches the report
#   now (#1027), but the class is promoted only once that data shows which of its lines the server
#   leaves missed, so it stays excluded here.
# - GrugRecipeHelper is a shim around the package-private CraftingManager.registerShaped, and
#   GrugRecipeParser is the part of it that needs a running game's registries to answer: whether an id
#   names an item or a tag is that answer. Both are per-loader, and the rules they apply are measured
#   where they can be, in GrugRecipeTree and GrugTags.
# - Render/resource glue only runs through the loader's own resource and GUI APIs, which a headless
#   CI cannot drive without a per-loader test harness. The testable logic behind them lives in
#   net.grug.* helpers (GrugResourceIndex, GrugGuiBuilder, GrugFaceTextures) which stay measured.
EXCLUDED_CLASSES = {
    "GenericHostFunctions",
    "MinecraftMixin",
    "MinecraftTimerMixin",
    "MinecraftServerTickMixin",
    "ThreadedLevelLightEngineMixin",
    "LayerLightSectionStorageMixin",
    "TitleScreenMixin",
    "ResourcePackManagerMixin",
    "CraftingManagerMixin",
    "BlockRendererMixin",
    "TesselatorMixin",
    "TessellatorMixin",
    "ItemRendererMixin",
    "ArsenicItemRendererMixin",
    "ArsenicOverlayRendererMixin",
    "BlockRenderManagerMixin",
    "WorldChunkMixin",
    "BlockEntityAccess",
    "BlockWithEntityMixin",
    "ChunkMixin",
    "GrugMixin",
    "GuiMixin",
    "GameGuiMixin",
    "InGameHudMixin",
    "LiquidBlockRendererMixin",
    "ModelBlockRendererMixin",
    "LightTextureMixin",
    "SectionBufferBuilderPoolMixin",
    "GameRendererMixin",
    "FogRendererMixin",
    "ServerGrugModLoader",
    # The two client-side mixins that order a single-player click against the runner (#1094): a
    # mixin's methods are copied into its target and never run in place, so JaCoCo cannot measure
    # them. The counts they keep are in GrugClientClicks, which a coverage run exercises.
    "MultiPlayerGameModeMixin",
    "ServerGamePacketListenerMixin",
    "GrugRecipeHelper",
    "GrugRecipeParser",
    "GrugResourcePack",
    "GrugPackResources",
    "GrugScreen",
    "GrugMenu",
    "GrugScreenHandler",
    "StationGuiHelper",
    "GrugCraftingInventory",
    "GrugStaticTexture",
    "DummyCraftingInventory",
    # The mod manager's screen and the title screen button that opens it (#335): GUI glue over each
    # loader's own screen API. What it shows and does lives in core's GrugModsScreenModel.
    "GrugModsScreen",
    "GrugModsButton",
    "GrugTitleButton",
    # The overlay's screen hooks: which screens an overlay is asked on and the draw of what it
    # recorded into the open screen's GuiGraphics. Only a real game with a screen up runs them, and
    # the pass bookkeeping they drive is measured in core's GrugOverlay.
    "GrugOverlayHost",
    "HandledScreenMixin",
    "InventoryMenuScreenMixin",
    "ScreenMixin",
    "GrugFillInvoker",
    # Loader startup and registration only runs inside a real game bootstrap, which a headless CI
    # cannot drive. The logic they carry beyond wiring (file classification, the LICENSE gate,
    # recipe parsing, the adapters) lives in measured classes instead.
    "GrugModLoader",
    "ClientGrugModLoader",
    "InitListener",
    "ClientInitListener",
    "ClientModEvents",
    # The client tick hook drives the run, the resolution swap and the cursor readout; the window
    # and GL state machine behind those live in core's GrugRunWindow, which core's own tests
    # measure, and what remains here is loader-specific glue over the game's window and GL APIs.
    "GrugClientHooks",
    # The loader's own block/entity and registration implementations only run through the game's
    # world and GUI APIs, which a headless CI cannot drive. The game-independent logic they use
    # (inventory math, entity handles, file classification) lives in measured core classes.
    "GrugBlockEntity",
    "GrugBlock",
    "GrugBlocks",
    # Draws a block entity's own geometry into Alpha's chunk compile. The class carries
    # GrugGenerated, so JaCoCo already drops it; this entry says so in one place next to the other
    # loader exclusions rather than leaving it to be rediscovered. The pass bookkeeping and box it
    # works from are game-independent and measured in core's GrugRenderPass and GrugBox.
    "GrugBoxRenderer",
    # 1.20.6's draw loop: a BakedModel that runs the pass during chunk meshing and emits the boxes
    # as quads. Same reason as GrugBoxRenderer.
    "GrugBakedModel",
    # Render/model glue, like the resource-pack classes above.
    "GrugBlockModels",
    # 1.2.5 Forge's own glue, for the same reason as the entries above: its entry point, its menu,
    # its GUI bridge and its item implementation only run inside a real game bootstrap. Its adapter,
    # its block-entity NBT accessor and the name table its tests reach are measured like every other
    # loader's, now that the loader contributes exec data (#580).
    "mod_Grug",
    # The client halves split out of mod_Grug for the dedicated server (#166): the render hook FML
    # calls and the registrations only a client makes. Same reason as mod_Grug.
    "GrugClientMod",
    "GrugClientSetup",
    # The reflection helper behind the overlay's screen geometry: the second field name, and the
    # catches around both lookups, are paths only a development run or a broken build takes, so the
    # class is excluded like the overlay's own screen glue rather than annotated line by line.
    "GrugGuiAccess",
    "GlBridge",
    "GrugContainer",
    "GrugItem",
    # The screen TestTickContext.close_screen displays on 1.2.5 so the headless focus check does not open the
    # in-game menu over a screenshot. Same reason as GrugScreen: GUI glue a headless CI cannot
    # measure beyond the run that uses it.
    "GrugEmptyScreen",
}


def filtered_classes(source: Path, destination: Path) -> Path:
    """Copy source's class files to destination, dropping the excluded classes."""
    if not EXCLUDED_CLASSES:
        return source
    # Start clean so a class that was copied by an earlier run cannot survive a new exclusion.
    shutil.rmtree(destination, ignore_errors=True)
    for path in source.rglob("*.class"):
        if path.stem.split("$", 1)[0] in EXCLUDED_CLASSES:
            continue
        target = destination / path.relative_to(source)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    return destination


def discover_runners(artifacts: Path) -> dict[str, dict]:
    runners: dict[str, dict] = {}
    server_execs: dict[str, list[Path]] = {}
    server_dropped: dict[str, list[Path]] = {}
    for entry in sorted(artifacts.iterdir()):
        if not entry.is_dir():
            continue
        if entry.name.startswith("coverage-exec-"):
            runner = entry.name[len("coverage-exec-") :]
            info = runners.setdefault(runner, {})
            info["exec"] = sorted(entry.glob("*.exec"))
            info["dropped"] = sorted(entry.glob("*.dropped"))
        elif entry.name.startswith("coverage-classes-"):
            runner = entry.name[len("coverage-classes-") :]
            runners.setdefault(runner, {})["classes"] = entry
        elif entry.name.startswith(LAYER):
            jars = sorted(entry.glob("*.jar"))
            if jars:
                runners.setdefault(entry.name[len(LAYER) :], {})["layer_jar"] = jars[0]
        elif entry.name.startswith(SERVER_EXEC):
            runner = entry.name[len(SERVER_EXEC) :]
            server_execs.setdefault(runner, []).extend(sorted(entry.glob("*.exec")))
            # The other mods' segments of the server are .dropped like the client's, and
            # rescue_class_initializers reads them to count a class initializer that first ran
            # there (#1309).
            server_dropped.setdefault(runner, []).extend(sorted(entry.glob("*.dropped")))
    # The dedicated server's exec data is appended to that loader's list rather than reported on its
    # own (#1027): the runner then has one report over its classes, in which the server's coverage
    # and the client's are a union. A second report would be summed with the first by sum_csvs,
    # which adds missed counts, so the loader's misses would be counted twice and fail the gate for
    # a line the server covered. A run with no multiplayer job, or a leg that recorded nothing, has
    # no artifact here at all, which is no server data rather than an error.
    #
    # What the server adds is not every class of the leg. JaCoCo matches a class by its ID, so it
    # analyses only exec data recorded against the very bytes staged for that runner. Core is those
    # bytes on every leg, since the server loads the same core classes the client does. A loader's
    # own server-only classes count on 1.20.6-forge and b1.7.3-stationapi, whose runServer launches
    # the staged dev classes, and on 1.2.5-forge, whose coverageClasses also stages the classes only
    # its reobfuscated server jar has (#1324). The three Ornithe legs' servers run the layer jar
    # instead, whose bytes differ from the client's dev classes for every class that names a renamed
    # Minecraft type: server_class_set analyses the classes only the server ran from that jar
    # against the server's exec data, in a second report of the same runner that holds those classes
    # and no others (#169). A class both runs ran with other bytes stays the client's copy, and a
    # 1.2.5 class that both jars have is the client's copy too: counting those would need a
    # line-level union over the two sets of bytes, never a second report over the same classes
    # summed by sum_csvs (#1027). main() prints each runner's dropped classes so the report says it.
    for runner, execs in server_execs.items():
        info = runners.setdefault(runner, {})
        info["exec"] = [*info.get("exec", []), *execs]
        info["server_exec"] = execs
        # Loud enough to tell a coverage job that held the artifacts from one that never got them,
        # since a leg that recorded nothing and a job that downloaded nothing look the same.
        print(f"appending {len(execs)} dedicated-server exec file(s) to {runner}'s report")
        dropped = server_dropped.get(runner, [])
        info["dropped"] = [*info.get("dropped", []), *dropped]
        info["server_dropped"] = dropped
        if dropped:
            print(f"appending {len(dropped)} dedicated-server dropped file(s) to {runner}'s report")
    return runners


def source_dirs(runner: str) -> list[Path]:
    if runner == CORE:
        return [
            Path("core/src/main/java"),
            Path("core/build/generated/sources/grug"),
        ]
    dirs = [Path("loaders") / runner / "src/main/java"]
    if runner in ORNITHE_LOADERS:
        dirs.append(Path("shared/ornithe/src/main/java"))
    return dirs


def exec_blocks(data: bytes):
    """Yield (kind, name, raw) for each block of a JaCoCo exec file; name is set for class data.

    The format (ExecutionDataWriter): a header block 0x01 with a two byte magic and a two byte
    version, session blocks 0x10 (a UTF id and two longs) and class blocks 0x11 (a long id, a UTF
    name and a probe array, which is a variable length integer of the probe count and the packed
    bits). The raw bytes of every block are kept so a filtered file is written back unchanged.
    """
    position = 0
    total = len(data)
    while position < total:
        start = position
        kind = data[position]
        position += 1
        name = None
        if kind == 0x01:
            position += 4
        elif kind == 0x10:
            position += 2 + int.from_bytes(data[position : position + 2], "big") + 16
        elif kind == 0x11:
            position += 8
            size = int.from_bytes(data[position : position + 2], "big")
            name = data[position + 2 : position + 2 + size].decode("utf-8", errors="replace")
            position += 2 + size
            count = shift = 0
            while True:
                byte = data[position]
                position += 1
                count |= (byte & 0x7F) << shift
                shift += 7
                if not byte & 0x80:
                    break
            position += (count + 7) // 8
        else:
            raise ValueError(f"unknown exec block type {kind:#x} at offset {start}")
        yield kind, name, data[start:position]


def exec_class_names(paths: list[Path]) -> set[str]:
    """The names of the classes the exec files hold data for."""
    names: set[str] = set()
    for path in paths:
        names.update(name for _, name, _ in exec_blocks(path.read_bytes()) if name is not None)
    return names


def exec_without(paths: list[Path], names: set[str], out_dir: Path) -> list[Path]:
    """Copies of the exec files with the data of the named classes left out.

    JaCoCo warns for exec data recorded against bytes other than the analysed ones, and a class
    the client set keeps with the client's bytes has the dedicated server's data recorded against
    the other bytes, so that data is not handed to the report that cannot use it.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    for index, path in enumerate(paths):
        kept = b"".join(
            raw
            for _, name, raw in exec_blocks(path.read_bytes())
            if name is None or name not in names
        )
        target = out_dir / f"{index:05d}-{path.name}"
        target.write_bytes(kept)
        written.append(target)
    return written


def base_server_exec(base_dir: Path | None) -> dict[str, tuple[list[Path], list[Path]]]:
    """The exec and dropped files of the base run's server-exec-<loader> artifacts, per runner.

    Each artifact sits in a directory of its own under base_dir, named as it was uploaded. A
    missing directory or one with no such artifact answers nothing: the base run ran no dedicated
    leg, or its retention (7 days) ended, and the workflow step says which (#1602).
    """
    found: dict[str, tuple[list[Path], list[Path]]] = {}
    if base_dir is None or not base_dir.is_dir():
        return found
    for entry in sorted(base_dir.iterdir()):
        if not entry.is_dir() or not entry.name.startswith(SERVER_EXEC):
            continue
        execs = sorted(entry.glob("*.exec"))
        if execs:
            found[entry.name[len(SERVER_EXEC) :]] = (execs, sorted(entry.glob("*.dropped")))
    return found


def carry_over_server_exec(
    runners: dict[str, dict], base: dict[str, tuple[list[Path], list[Path]]]
) -> tuple[dict[str, tuple[list[Path], list[Path]]], list[str]]:
    """Which runners take the base run's dedicated-server exec data, and the lines that say why.

    A runner takes it only when this run's own leg uploaded none (its info has no server_exec),
    since the run's own data is the newer and a union of two recordings would be a second source
    for nothing. A runner this run has no classes for takes none either. The data is only
    appended to the runner's exec list: JaCoCo analyses it against this run's class bytes and
    ignores a class whose id no longer matches, which counts as nothing and never as an error
    (#1602). It is never the Ornithe server set, since that needs this run's layer jar.
    """
    taken: dict[str, tuple[list[Path], list[Path]]] = {}
    lines: list[str] = []
    if not base:
        lines.append("no dedicated-server exec data carried over: the base run has none to carry")
        return taken, lines
    for runner, (execs, dropped) in sorted(base.items()):
        info = runners.get(runner)
        if info is None or "classes" not in info:
            lines.append(f"{runner}: base run's server exec not carried: this run has no classes")
        elif info.get("server_exec"):
            lines.append(f"{runner}: this run's own leg uploaded server exec, none carried over")
        else:
            taken[runner] = (execs, dropped)
            lines.append(
                f"{runner}: carrying over {len(execs)} dedicated-server exec file(s) of the base run"
            )
    return taken, lines


def carried_unmatched(unmatched: list[str], carried: list[Path]) -> list[str]:
    """The classes JaCoCo dropped whose exec data came from the base run, sorted.

    Their ids no longer match this run's bytes, so the base run's recording says nothing about
    them: they count as nothing, which is not a miss of anything the run covered (#1602).
    """
    return sorted(set(unmatched) & exec_class_names(carried))


def server_class_set(
    layer_jar: Path, client_classes: Path, client_names: set[str], work: Path
) -> tuple[Path, Path, list[str], set[str]]:
    """Split an Ornithe runner's classes into the client's set and the dedicated server's own.

    The dedicated server runs the layer jar, whose bytes differ from the client run's dev classes
    for every class that names a renamed Minecraft type, and JaCoCo analyses exec data only against
    the bytes it was recorded on. A class whose bytes differ and which the client run never
    initialised (client_names holds the ones it did) can only have the server's exec data, so it is
    analysed from the layer jar's copy alone and left out of the client set: no class is counted in
    two reports, which sum_csvs would add up as two sets of misses (#169, #1027). A class both runs
    ran with other bytes stays the client's copy, as it always was, since counting it from both
    needs a line-level union of two sets of bytes. Returns the client set, the server set, the
    sorted names of the classes that moved and the names of the differing ones that stayed.
    """
    shutil.rmtree(work, ignore_errors=True)
    layer_dir = work / "layer"
    with zipfile.ZipFile(layer_jar) as jar:
        for name in jar.namelist():
            if name.endswith(".class"):
                target = layer_dir / name
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(jar.read(name))
    layer_dir = filtered_classes(layer_dir, work / "layer-filtered")
    client_dir = work / "client"
    server_dir = work / "server"
    client_dir.mkdir(parents=True, exist_ok=True)
    server_dir.mkdir(parents=True, exist_ok=True)
    moved: list[str] = []
    stayed: set[str] = set()
    for path in sorted(layer_dir.rglob("*.class")):
        relative = path.relative_to(layer_dir)
        name = relative.with_suffix("").as_posix()
        counterpart = client_classes / relative
        if counterpart.is_file() and counterpart.read_bytes() == path.read_bytes():
            continue
        if name in client_names:
            stayed.add(name)
            continue
        moved.append(name)
        target = server_dir / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    for path in client_classes.rglob("*.class"):
        relative = path.relative_to(client_classes)
        if relative.with_suffix("").as_posix() in moved:
            continue
        target = client_dir / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(path, target)
    return client_dir, server_dir, sorted(moved), stayed


def jacoco_report(
    cli: Path,
    execs: list[Path],
    classes: Path,
    sources: list[Path],
    csv_path: Path,
    xml_path: Path,
) -> list[str]:
    """Run the JaCoCo report and return the classes whose exec data it dropped as not matching."""
    command = ["java", "-jar", str(cli), "report", *map(str, execs)]
    command += ["--classfiles", str(classes)]
    for source in sources:
        if source.is_dir():
            command += ["--sourcefiles", str(source)]
    command += ["--csv", str(csv_path), "--xml", str(xml_path)]
    result = subprocess.run(
        command, check=False, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
    )
    # The CLI's own output stays in the log, as it did when it wrote to the terminal, and a failing
    # run's output is the one that says why, so it is printed before the failure is raised.
    print(result.stdout, end="")
    result.check_returncode()
    return sorted(set(DOES_NOT_MATCH.findall(result.stdout)))


def class_id(data: bytes) -> int:
    """JaCoCo's class id: the CRC64 (reflected, polynomial 0xd800000000000000) of the class bytes."""
    table = []
    for index in range(256):
        value = index
        for _ in range(8):
            value = (value >> 1) ^ 0xD800000000000000 if value & 1 else value >> 1
        table.append(value)
    total = 0
    for byte in data:
        total = (total >> 8) ^ table[(total ^ byte) & 0xFF]
    return total


def unmask_generated(data: bytes) -> bytes:
    """The class bytes with every @GrugGenerated annotation renamed so JaCoCo no longer filters it."""
    return data.replace(GENERATED_DESCRIPTOR, UNMASKED_DESCRIPTOR)


def exec_with_ids(paths: list[Path], ids: dict[str, int], out_dir: Path) -> list[Path]:
    """Copies of the exec files that hold only the named classes' data, under their new ids."""
    out_dir.mkdir(parents=True, exist_ok=True)
    written: list[Path] = []
    for index, path in enumerate(paths):
        kept = b""
        for kind, name, raw in exec_blocks(path.read_bytes()):
            if name is None:
                kept += raw
            elif name in ids:
                kept += raw[:1] + ids[name].to_bytes(8, "big") + raw[9:]
        target = out_dir / f"{index:05d}-{path.name}"
        target.write_bytes(kept)
        written.append(target)
    return written


def measure_server_only(
    runner: str,
    class_dir: Path,
    execs: list[Path],
    jacoco_cli: Path,
    work: Path,
) -> tuple[list[tuple[str, int, int, int, int]], set[str]]:
    """What the gate would count for the runner's server-only classes with their exclusion off.

    The classes of SERVER_ONLY[runner] found in class_dir are copied with their @GrugGenerated
    renamed, the exec data is cut to them and rewritten to the renamed bytes' ids, and a JaCoCo
    report over them gives (class, instructions missed, covered, branches missed, covered) for each
    class and nested class, and the names of the classes the exec data holds anything for (a class
    outside that set was never loaded, or was loaded with other bytes than class_dir's). A class
    the report does not name is not in class_dir. The measurement is not a gate input (#1600).
    """
    wanted = set(SERVER_ONLY.get(runner, []))
    shutil.rmtree(work, ignore_errors=True)
    classes = work / "classes"
    ids: dict[str, int] = {}
    for path in sorted(class_dir.rglob("*.class")):
        if path.stem.split("$", 1)[0] not in wanted:
            continue
        patched = unmask_generated(path.read_bytes())
        target = classes / path.relative_to(class_dir)
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(patched)
        ids[path.relative_to(class_dir).with_suffix("").as_posix()] = class_id(patched)
    if not ids:
        return [], set()
    cut = exec_with_ids(execs, ids, work / "exec")
    seen = exec_class_names(cut)
    csv_path = work / "measure.csv"
    jacoco_report(
        jacoco_cli,
        cut,
        classes,
        [],
        csv_path,
        work / "measure.xml",
    )
    rows = []
    with csv_path.open(newline="") as handle:
        for row in csv.DictReader(handle):
            rows.append(
                (
                    row["CLASS"],
                    int(row["INSTRUCTION_MISSED"]),
                    int(row["INSTRUCTION_COVERED"]),
                    int(row["BRANCH_MISSED"]),
                    int(row["BRANCH_COVERED"]),
                )
            )
    return sorted(rows), seen


def report_server_only(
    runner: str, info: dict, execs: list[Path], jacoco_cli: Path, work: Path
) -> None:
    """Print what the runner's server-only classes would count with their exclusion off (#1600).

    A class the client run also ran, or that sits in the staged tree beside the server's (1.20.6,
    StationAPI and 1.2.5 stage the very bytes their servers run), is measured from the staged
    classes against every exec file. An Ornithe server runs the layer jar, so its classes are
    measured from the jar's bytes against the server's exec data alone.
    """
    measure_dir = work / "server-only" / runner
    raw_classes = info["classes"]
    where = "staged"
    if runner in ORNITHE_LOADERS:
        if not info.get("server_exec") or info.get("layer_jar") is None:
            print(f"server-only {runner}: no dedicated-server exec data, not measured")
            return
        raw_classes = measure_dir / "layer"
        shutil.rmtree(raw_classes, ignore_errors=True)
        with zipfile.ZipFile(info["layer_jar"]) as jar:
            jar.extractall(raw_classes)
        where = "layer jar"
        execs = info["server_exec"]
    rows, seen = measure_server_only(runner, raw_classes, execs, jacoco_cli, measure_dir / "report")
    print_measurements(runner, rows, seen, where)


def print_measurements(
    runner: str, rows: list[tuple[str, int, int, int, int]], seen: set[str], where: str
) -> None:
    """One line per measured class, so the coverage job's log names a missed count for each.

    The names are the class files' (package/Outer$Inner), the rows' are JaCoCo's CSV ones, so a row
    matches an exec name by its last path segment with the dots of a nested class as dollars.
    """
    seen_names = {name.rsplit("/", 1)[-1] for name in seen}
    for name, missed, covered, branch_missed, branch_covered in rows:
        note = "" if name.replace(".", "$") in seen_names else " (no exec data: not run)"
        print(
            f"server-only {runner} {name}: {missed} instructions and {branch_missed} branches "
            f"missed ({covered} and {branch_covered} covered) {where}{note}"
        )
    found = {name.split("$", 1)[0] for name, *_ in rows}
    for name in SERVER_ONLY.get(runner, []):
        if name not in found:
            print(f"server-only {runner} {name}: not in the {where} class bytes, not measured")


# JaCoCo's warning for exec data recorded against a class whose bytes differ from the analysed ones.
DOES_NOT_MATCH = re.compile(r"Execution data for class (\S+) does not match", re.MULTILINE)


def print_unmatched(runner: str, unmatched: list[str]) -> None:
    """Say which classes the exec data cannot count for a runner, and why (#1324)."""
    if not unmatched:
        return
    print(
        f"{runner}: exec data of {len(unmatched)} class(es) does not match the staged class bytes "
        "and is not counted (the dedicated server's reobfuscated jar has other bytes for a class "
        "that names a renamed Minecraft type, and the client's copy is what the report analyses): "
        + ", ".join(unmatched)
    )


# The counters a <clinit> and its ancestors carry. INSTRUCTION and BRANCH are the gate's; the
# others are what the uploaded report shows, and the CSV and XML have to agree on all of them, or a
# passing run's per-line report reads as if the initializer were still missed (#897).
COUNTERS = ("INSTRUCTION", "BRANCH", "LINE", "COMPLEXITY", "METHOD")
CSV_COLUMNS = {kind: (f"{kind}_MISSED", f"{kind}_COVERED") for kind in COUNTERS}


def class_initializer_counters(xml_path: Path) -> dict[tuple[str, str], dict[str, tuple[int, int]]]:
    """The counters of every <clinit> in a JaCoCo XML report.

    Keyed by (package, class) as the CSV names them: a nested class is Outer.Inner there and
    Outer$Inner in the XML. The value maps each of COUNTERS to (missed, covered).
    """
    counters: dict[tuple[str, str], dict[str, tuple[int, int]]] = {}
    for package in ET.parse(xml_path).getroot().iter("package"):
        package_name = package.get("name", "").replace("/", ".")
        for cls in package.iter("class"):
            simple_name = cls.get("name", "").rsplit("/", 1)[-1].replace("$", ".")
            for method in cls.iter("method"):
                if method.get("name") != "<clinit>":
                    continue
                counts = {
                    counter.get("type", ""): (
                        int(counter.get("missed", "0")),
                        int(counter.get("covered", "0")),
                    )
                    for counter in method.iter("counter")
                    if counter.get("type") in COUNTERS
                }
                counters[(package_name, simple_name)] = counts
    return counters


def move_counter(element: ET.Element, kind: str, gained: int) -> bool:
    """Move one counter child of element to covered, or return False when it has none."""
    for counter in element.findall("counter"):
        if counter.get("type") == kind:
            counter.set("missed", str(int(counter.get("missed", "0")) - gained))
            counter.set("covered", str(int(counter.get("covered", "0")) + gained))
            return True
    return False


CLASS_FILE_MAGIC = 0xCAFEBABE


class ClassFileReader:
    """An in-order reader of a class file's bytes, for the parts the report needs."""

    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def u1(self) -> int:
        value = self.data[self.pos]
        self.pos += 1
        return value

    def u2(self) -> int:
        if self.pos + 2 > len(self.data):
            raise IndexError("truncated class file")
        value = int.from_bytes(self.data[self.pos : self.pos + 2], "big")
        self.pos += 2
        return value

    def u4(self) -> int:
        if self.pos + 4 > len(self.data):
            raise IndexError("truncated class file")
        value = int.from_bytes(self.data[self.pos : self.pos + 4], "big")
        self.pos += 4
        return value

    def skip(self, count: int) -> None:
        self.pos += count

    def read(self, count: int) -> bytes:
        if self.pos + count > len(self.data):
            raise IndexError("truncated class file")
        value = self.data[self.pos : self.pos + count]
        self.pos += count
        return value


def skip_attributes(reader: ClassFileReader) -> None:
    """Skip a member's or class's attribute table."""
    for _ in range(reader.u2()):
        reader.skip(2)
        reader.skip(reader.u4())


def line_numbers(reader: ClassFileReader, utf8: dict[int, str]) -> list[int]:
    """The lines of a Code attribute's LineNumberTable, read from inside the attribute."""
    reader.skip(4)  # max_stack, max_locals
    reader.skip(reader.u4())  # code_length
    reader.skip(8 * reader.u2())  # exception_table
    lines = []
    for _ in range(reader.u2()):
        attribute = utf8.get(reader.u2(), "")
        length = reader.u4()
        end = reader.pos + length
        if attribute == "LineNumberTable":
            for _ in range(reader.u2()):
                reader.skip(2)  # start_pc
                lines.append(reader.u2())
        reader.pos = end
    return lines


def class_method_lines(class_file: Path) -> dict[str, set[int]] | None:
    """The source lines each method of a class file has code on, or None when unreadable.

    This is the class file's own LineNumberTable, which is where JaCoCo's report takes its line
    numbers from. An initializer's lines are not always adjacent: a static field declared below a
    method puts its initializer in the same <clinit> on the field's own line, so guessing a
    contiguous span from the method's first line misses lines and can claim another's (#902).
    """
    try:
        reader = ClassFileReader(class_file.read_bytes())
        if reader.u4() != CLASS_FILE_MAGIC:
            return None
        reader.skip(4)  # minor, major
        utf8: dict[int, str] = {}
        count = reader.u2()
        index = 1
        while index < count:
            tag = reader.u1()
            if tag == 1:
                utf8[index] = reader.read(reader.u2()).decode("utf-8", "replace")
            elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
                reader.skip(4)
            elif tag in (5, 6):
                reader.skip(8)
                index += 1  # a long or a double takes two constant pool slots
            elif tag in (7, 8, 16, 19, 20):
                reader.skip(2)
            elif tag == 15:
                reader.skip(3)
            else:
                return None
            index += 1
        reader.skip(6)  # access_flags, this_class, super_class
        reader.skip(2 * reader.u2())  # interfaces
        for _ in range(reader.u2()):  # fields
            reader.skip(6)
            skip_attributes(reader)
        methods: dict[str, set[int]] = {}
        for _ in range(reader.u2()):
            reader.skip(2)  # access_flags
            name = utf8.get(reader.u2(), "")
            reader.skip(2)  # descriptor
            lines: set[int] = set()
            for _ in range(reader.u2()):
                attribute = utf8.get(reader.u2(), "")
                length = reader.u4()
                end = reader.pos + length
                if attribute == "Code":
                    lines.update(line_numbers(reader, utf8))
                reader.pos = end
            methods.setdefault(name, set()).update(lines)
        return methods
    except (OSError, IndexError):
        return None


def initializer_source_lines(
    cls: ET.Element, package: ET.Element, classes: Path
) -> tuple[set[int] | None, str]:
    """The lines the class's <clinit> alone has code on, or why the report cannot say.

    A line the initializer shares with another method of this class, or with another class of the
    same source file, is not its own: moving it would count that method's coverage too, so the
    caller leaves the class's XML record untouched instead.
    """
    class_lines = class_method_lines(classes / (cls.get("name", "") + ".class"))
    if class_lines is None:
        return None, "its class file could not be read"
    lines = class_lines.get("<clinit>")
    if not lines:
        return None, "its class file has no line table for its initializer"
    others = set().union(*(value for name, value in class_lines.items() if name != "<clinit>"))
    shared = lines & others
    if shared:
        return None, (
            "line(s) " + ", ".join(map(str, sorted(shared))) + " also hold another method's code"
        )
    source_name = cls.get("sourcefilename")
    for other in package.findall("class"):
        if other.get("name") == cls.get("name"):
            continue
        if other.get("sourcefilename") != source_name:
            continue
        other_lines = class_method_lines(classes / (other.get("name", "") + ".class"))
        if other_lines is None:
            return None, f"the class file of {other.get('name')} could not be read"
        if lines & set().union(*other_lines.values()):
            return None, f"another class of {source_name} has code on the same line(s)"
    return lines, ""


def correct_xml(
    counted_xml: Path,
    everything_xml: Path,
    rescued: dict[tuple[str, str], dict[str, int]],
    classes: Path,
) -> None:
    """Move the rescued counters in the counted XML too, so the uploaded report agrees with the CSV.

    The <clinit> method's counters and lines move, and every ancestor's counters move by the same
    amounts, so the report stays internally consistent: a run whose initializer only another mod's
    test ran no longer uploads a report that says it was missed (#897). The lines are the ones the
    class file's LineNumberTable gives the initializer, never a guessed span; where they cannot be
    shown to be the initializer's alone, the class's XML is left as it was and the CSV remains the
    gate's record, rather than claiming a line another method's code is on (#902 review).
    """
    tree = ET.parse(counted_xml)
    root = tree.getroot()
    everything_root = ET.parse(everything_xml).getroot()
    everything_packages = {
        package.get("name", ""): package for package in everything_root.findall("package")
    }
    report_gained: dict[str, int] = {}
    for package in root.findall("package"):
        everything_package = everything_packages.get(package.get("name", ""))
        if everything_package is None:
            continue
        package_gained: dict[str, int] = {}
        everything_classes = {
            cls.get("name", ""): cls for cls in everything_package.findall("class")
        }
        for cls in package.findall("class"):
            key = (
                package.get("name", "").replace("/", "."),
                cls.get("name", "").rsplit("/", 1)[-1].replace("$", "."),
            )
            gained = rescued.get(key)
            if not gained:
                continue
            everything_cls = everything_classes.get(cls.get("name", ""))
            method = next(
                (method for method in cls.findall("method") if method.get("name") == "<clinit>"),
                None,
            )
            if everything_cls is None or method is None:
                continue
            lines, why = initializer_source_lines(cls, package, classes)
            source_name = cls.get("sourcefilename")
            counted_source = next(
                (
                    source
                    for source in package.findall("sourcefile")
                    if source.get("name") == source_name
                ),
                None,
            )
            everything_source = next(
                (
                    source
                    for source in everything_package.findall("sourcefile")
                    if source.get("name") == source_name
                ),
                None,
            )
            if counted_source is None or everything_source is None:
                lines, why = None, "its source file is missing from a report"
            counted_lines: dict[str, ET.Element] = {}
            everything_lines: dict[str, ET.Element] = {}
            if lines is not None:
                counted_lines = {
                    line.get("nr", ""): line for line in counted_source.findall("line")
                }
                everything_lines = {
                    line.get("nr", ""): line for line in everything_source.findall("line")
                }
                missing = sorted(
                    number
                    for number in lines
                    if str(number) not in counted_lines or str(number) not in everything_lines
                )
                if missing:
                    lines, why = (
                        None,
                        ("line(s) " + ", ".join(map(str, missing)) + " missing from a report"),
                    )
            if lines is None:
                print(
                    f"left the XML record of {key[0]}.{key[1]} untouched: {why}, so the CSV"
                    f" counts its initializer and the per-line report does not"
                )
                continue
            applied = {
                kind: amount
                for kind, amount in gained.items()
                if amount > 0 and move_counter(method, kind, amount)
            }
            for kind, amount in applied.items():
                move_counter(cls, kind, amount)
            # A class the initializer alone had kept uncovered is covered now, and its CLASS
            # counter, which the CSV has no column for, has to say so at every level.
            for counter in cls.findall("counter"):
                if counter.get("type") == "CLASS" and int(counter.get("missed", "0")) > 0:
                    instruction = next(
                        (
                            counter
                            for counter in cls.findall("counter")
                            if counter.get("type") == "INSTRUCTION"
                        ),
                        None,
                    )
                    if instruction is not None and int(instruction.get("covered", "0")) > 0:
                        counter.set("missed", "0")
                        counter.set("covered", "1")
                        applied["CLASS"] = 1
                    break
            source = next(
                (
                    source
                    for source in package.findall("sourcefile")
                    if source.get("name") == source_name
                ),
                None,
            )
            if source is not None:
                for kind, amount in applied.items():
                    move_counter(source, kind, amount)
            for kind, amount in applied.items():
                package_gained[kind] = package_gained.get(kind, 0) + amount
                report_gained[kind] = report_gained.get(kind, 0) + amount
            for number in lines:
                counted_line = counted_lines[str(number)]
                everything_line = everything_lines[str(number)]
                for attribute in ("mi", "ci", "mb", "cb"):
                    counted_line.set(attribute, everything_line.get(attribute, "0"))
        for kind, amount in package_gained.items():
            move_counter(package, kind, amount)
    for kind, amount in report_gained.items():
        move_counter(root, kind, amount)
    tree.write(counted_xml, encoding="utf-8", xml_declaration=True)


def rescue_class_initializers(
    csv_path: Path, counted_xml: Path, everything_xml: Path, classes: Path
) -> None:
    """Count a class initializer that ran in a dropped segment as covered, in csv_path and the XML.

    csv_path and counted_xml are the report of the segments the gate counts, and everything_xml the
    report of those and the other mods' segments. A <clinit> that the second shows covered and the
    first missed ran only in another mod's segment, which is where a class's initializer runs when
    another mod's test is the first to use the class, and no test of mods/coverage/ can make it run
    again. Only the <clinit> of a class is moved; whatever else such a segment reached stays
    uncounted. The XML moves with the CSV, because it is the per-line report a failure is read
    from, and classes is the class file tree the report analyzed, whose line tables say which
    lines the initializer owns.
    """
    counted = class_initializer_counters(counted_xml)
    everything = class_initializer_counters(everything_xml)
    rescued: dict[tuple[str, str], dict[str, int]] = {}
    with csv_path.open(newline="") as handle:
        reader = csv.DictReader(handle)
        fieldnames = list(reader.fieldnames or [])
        rows = list(reader)
    for row in rows:
        key = (row["PACKAGE"], row["CLASS"])
        if key not in counted or key not in everything:
            continue
        gained: dict[str, int] = {}
        for kind, (missed_column, covered_column) in CSV_COLUMNS.items():
            # A <clinit> without a branch has no BRANCH counter at all.
            gain = counted[key].get(kind, (0, 0))[0] - everything[key].get(kind, (0, 0))[0]
            if gain > 0 and missed_column in row:
                row[missed_column] = str(int(row[missed_column]) - gain)
                row[covered_column] = str(int(row[covered_column]) + gain)
                gained[kind] = gain
        if gained:
            rescued[key] = gained
            print(
                f"counted the class initializer of {key[0]}.{key[1]}, which only ran in another"
                f" mod's tests: {gained}"
            )
    with csv_path.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(fieldnames))
        writer.writeheader()
        writer.writerows(rows)
    if rescued:
        correct_xml(counted_xml, everything_xml, rescued, classes)


def sum_csvs(csv_paths: list[Path], out_path: Path) -> None:
    header: list[str] | None = None
    totals: dict[tuple[str, str, str], list] = {}
    for path in csv_paths:
        with path.open(newline="") as handle:
            reader = csv.reader(handle)
            rows = list(reader)
        if not rows:
            continue
        if header is None:
            header = rows[0]
        for row in rows[1:]:
            key = (row[0], row[1], row[2])
            values = totals.setdefault(key, [row[0], row[1], row[2]] + [0] * (len(row) - 3))
            for i in range(3, len(row)):
                values[i] += int(row[i])
    with out_path.open("w", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(header)
        for key in sorted(totals):
            writer.writerow(totals[key])


def print_totals(combined: Path) -> tuple[int, int, int, int]:
    missed = covered = branch_missed = branch_covered = 0
    with combined.open(newline="") as handle:
        for row in csv.DictReader(handle):
            missed += int(row["INSTRUCTION_MISSED"])
            covered += int(row["INSTRUCTION_COVERED"])
            branch_missed += int(row["BRANCH_MISSED"])
            branch_covered += int(row["BRANCH_COVERED"])

    def percent(not_covered: int, have_covered: int) -> str:
        total = not_covered + have_covered
        return f"{100 * have_covered / total:.1f}%" if total else "n/a"

    print(f"combined instruction coverage: {percent(missed, covered)}")
    print(f"combined branch coverage:      {percent(branch_missed, branch_covered)}")
    return missed, covered, branch_missed, branch_covered


def carried_by_runner(specs: list[str], option: str) -> dict[str, list[Path]] | None:
    """The RUNNER:EXEC specs of one carry-over option, grouped by runner, or None on a bad spec.

    An artifact that was renamed or expired is not a reason to fail a run: the gate then proves
    what it can from the exec data it has, and a line the missing file was the only cover for fails
    it, rather than passing on coverage nobody counted (#894).
    """
    grouped: dict[str, list[Path]] = {}
    for spec in specs:
        runner, sep, path = spec.partition(":")
        if not sep or not runner or not path:
            print(
                f"error: {option} needs <runner>:<exec>, not {spec!r}: a carried exec counts "
                "for the runner whose classes it was recorded against",
                file=sys.stderr,
            )
            return None
        exec_file = Path(path)
        if exec_file.is_file():
            grouped.setdefault(runner, []).append(exec_file)
        else:
            print(
                f"warning: {option} names {exec_file} for {runner}, which is not a file: it is "
                f"not counted, so any coverage only it held is missed",
                file=sys.stderr,
            )
    return grouped


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--artifacts", required=True, type=Path, help="Directory of downloaded artifacts"
    )
    parser.add_argument("--jacoco-cli", required=True, type=Path, help="Path to the JaCoCo CLI jar")
    parser.add_argument("--output", required=True, type=Path, help="Directory to write reports to")
    parser.add_argument(
        "--require-100",
        action="store_true",
        help="Exit non-zero unless instruction and branch coverage are both 100%%.",
    )
    parser.add_argument(
        "--carry-over",
        action="append",
        default=[],
        metavar="RUNNER:EXEC",
        help=(
            "The exec file of a coverage test's recorded coverage on one runner, carried over "
            "from the base commit's map (#786): counted for that runner like the .exec files of "
            "its tests that ran, and for core, which unions every runner's exec data, like the "
            "same. The tool reconstructs one exec per test and runner, since a test's coverage of "
            "a loader's own classes only counts for that loader."
        ),
    )
    parser.add_argument(
        "--carry-over-dropped",
        action="append",
        default=[],
        metavar="RUNNER:EXEC",
        help=(
            "The exec file of the other mods' recorded coverage on one runner, carried over from "
            "the base commit's map (#897): counted for that runner and for core like the run's "
            "own other-mod segments, which means only a class initializer it made run is counted, "
            "through rescue_class_initializers."
        ),
    )
    parser.add_argument(
        "--base-server-exec",
        type=Path,
        default=None,
        help=(
            "A directory of the base run's server-exec-<loader> artifacts, one subdirectory each "
            "(#1602): a runner whose own run uploaded no dedicated-server exec data counts them "
            "like its own, so a diff that runs no dedicated leg keeps what the last run recorded."
        ),
    )
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    runners = discover_runners(args.artifacts)
    if CORE not in runners:
        print("error: no coverage-exec-core artifact found", file=sys.stderr)
        return 1

    carried_server, carried_server_lines = carry_over_server_exec(
        runners, base_server_exec(args.base_server_exec)
    )
    if args.base_server_exec is not None:
        for line in carried_server_lines:
            print(line)
    for runner, (server_execs, server_dropped_files) in carried_server.items():
        runners[runner]["exec"] = [*runners[runner].get("exec", []), *server_execs]
        runners[runner]["dropped"] = [*runners[runner].get("dropped", []), *server_dropped_files]

    carried = carried_by_runner(args.carry_over, "--carry-over")
    carried_dropped = carried_by_runner(args.carry_over_dropped, "--carry-over-dropped")
    if carried is None or carried_dropped is None:
        return 1
    carry_over = [path for paths in carried.values() for path in paths]
    carried_other_mods = [path for paths in carried_dropped.values() for path in paths]
    if carry_over:
        # One per test and runner: a test carried over on four loaders is four exec files.
        print(
            f"counting the recorded coverage of {len(carry_over)} carried-over exec file(s),"
            f" one per test and runner"
        )
    if carried_other_mods:
        print(
            f"counting the class initializers of {len(carried_other_mods)} carried-over other-mod"
            f" exec file(s)"
        )

    all_execs = [exec_file for info in runners.values() for exec_file in info.get("exec", [])]
    all_dropped = [dropped for info in runners.values() for dropped in info.get("dropped", [])]
    csv_paths: list[Path] = []
    for runner, info in sorted(runners.items()):
        execs = all_execs if runner == CORE else info.get("exec", [])
        if runner == CORE:
            # Every carried-over exec holds the core classes its test reached on the loader it
            # ran, and core's report unions every runner's exec data, so all of them count here.
            execs = [*execs, *carry_over]
        else:
            # The loader's own classes only count in that loader's report: the test's coverage of
            # a loader class is recorded against it, and no other runner can analyse it.
            execs = [*execs, *carried.get(runner, [])]
        if runner == CORE:
            # The other mods' segments are the run's dropped data, which core unions over every
            # runner too: their initializers count wherever they ran (#626, #897).
            dropped = [*all_dropped, *carried_other_mods]
        else:
            dropped = [*info.get("dropped", []), *carried_dropped.get(runner, [])]
        classes = info.get("classes")
        if not execs or classes is None:
            print(f"warning: skipping {runner}: missing exec data or class files", file=sys.stderr)
            continue
        # Kept out of coverage/report so it isn't uploaded with the report artifact.
        classes = filtered_classes(classes, args.output.parent / "filtered-classes" / runner)
        server_classes = None
        layer_jar = info.get("layer_jar")
        if runner in ORNITHE_LOADERS and info.get("server_exec"):
            if layer_jar is None:
                # Counting the client's copy alone would drop the server's data for every class
                # with other bytes without a word, which is the state this set exists to end.
                print(
                    f"error: {runner} has dedicated-server exec data but no layer-{runner} "
                    "artifact, so the server's classes cannot be analysed",
                    file=sys.stderr,
                )
                return 1
            work = args.output.parent / "class-sets" / runner
            server_only = set(info["server_exec"])
            server_dropped = set(info.get("server_dropped", []))
            client_names = exec_class_names(
                [p for p in execs if p not in server_only]
                + [p for p in dropped if p not in server_dropped]
            )
            classes, server_classes, moved, stayed = server_class_set(
                layer_jar, classes, client_names, work
            )
            if not moved:
                # An empty set has nothing to report, and its exec data would only be ignored.
                server_classes = None
            print(
                f"{runner}: {len(moved)} class(es) only the dedicated server ran have other bytes "
                "in its jar and are analysed from it against the server's exec data: "
                + ", ".join(moved)
            )
            # The data of the classes both runs ran with other bytes stays out of the client set's
            # report, which holds the client's copy of them.
            execs = [
                *(p for p in execs if p not in server_only),
                *exec_without(sorted(server_only), stayed, work / "server-exec"),
            ]
            dropped = [
                *(p for p in dropped if p not in server_dropped),
                *exec_without(sorted(server_dropped), stayed, work / "server-dropped"),
            ]
        csv_path = args.output / f"{runner}.csv"
        unmatched = jacoco_report(
            args.jacoco_cli,
            execs,
            classes,
            source_dirs(runner),
            csv_path,
            args.output / f"{runner}.xml",
        )
        print_unmatched(runner, unmatched or [])
        if runner in carried_server:
            stale = carried_unmatched(unmatched or [], carried_server[runner][0])
            if stale:
                print(
                    f"{runner}: the base run's server exec data of {len(stale)} class(es) no "
                    "longer matches this run's class bytes and counts as nothing: "
                    + ", ".join(stale)
                )
        if dropped:
            # Kept out of coverage/report like the filtered classes, since it is only an input.
            everything_xml = args.output.parent / "everything-reports" / f"{runner}.xml"
            everything_xml.parent.mkdir(parents=True, exist_ok=True)
            jacoco_report(
                args.jacoco_cli,
                [*execs, *dropped],
                classes,
                source_dirs(runner),
                everything_xml.with_suffix(".csv"),
                everything_xml,
            )
            rescue_class_initializers(
                csv_path, args.output / f"{runner}.xml", everything_xml, classes
            )
        csv_paths.append(csv_path)
        if runner in SERVER_ONLY:
            report_server_only(runner, info, execs, args.jacoco_cli, args.output.parent)
        if server_classes is not None:
            server_csv = args.output / f"{runner}-server.csv"
            unmatched = jacoco_report(
                args.jacoco_cli,
                info["server_exec"],
                server_classes,
                source_dirs(runner),
                server_csv,
                args.output / f"{runner}-server.xml",
            )
            print_unmatched(f"{runner} (server set)", unmatched or [])
            csv_paths.append(server_csv)
        print(f"reported {runner} from {len(execs)} exec files and {len(dropped)} dropped ones")

    combined = args.output / "combined.csv"
    sum_csvs(csv_paths, combined)
    missed, _covered, branch_missed, _branch_covered = print_totals(combined)
    if args.require_100 and (missed or branch_missed):
        print(
            "error: coverage is not 100% ("
            + str(missed)
            + " instructions and "
            + str(branch_missed)
            + " branches missed). The gate counts the unit tests, each game run's boot and"
            " mods/coverage/'s tests only, so a line that only another mod's test reaches is"
            " missed here: cover it from mods/coverage/",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
