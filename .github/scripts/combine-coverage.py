#!/usr/bin/env python3
"""Combine the per-runner JaCoCo artifacts that CI uploads into one coverage report.

The core job and every loader job upload:

    coverage-exec-<runner>/<name>.exec
    coverage-classes-<runner>/...

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
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

CORE = "core"

# These loaders compile shared/ornithe as an extra source directory.
ORNITHE_LOADERS = {"a1.1.2_01-ornithe", "b1.7.3-ornithe"}

# Classes deliberately left out of the combined report, kept in one place so the exclusions stay
# reviewable:
# - GenericHostFunctions is mechanically derived from one template and parameterized only by the
#   type tables in core/generate.py, so its per-permutation coverage adds nothing over testing those
#   tables directly (see core/test_generate.py). ExportFns is deliberately not excluded: its
#   remaining wrappers map to distinct callbacks.
# - Mixin classes can never be measured: Mixin copies a mixin's methods into its target without
#   defining the mixin class, so JaCoCo never instruments them. The logic they used to hold lives in
#   measured net.grug.* helpers instead.
# - ServerGrugModLoader only runs on a dedicated server, which CI does not launch.
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
    "TitleScreenMixin",
    "ResourcePackManagerMixin",
    "CraftingManagerMixin",
    "BlockRendererMixin",
    "TesselatorMixin",
    "TessellatorMixin",
    "ItemRendererMixin",
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
    # 1.2.5 Forge runs on Java 8 with no JaCoCo agent (the loader cannot be instrumented without a
    # Java 8 agent and a ModLoader-aware launch), so it contributes no exec data. Its own glue is
    # excluded for the same reason as the other loaders': the entry point, adapter, GUI and world
    # classes only run inside a real game bootstrap.
    "mod_Grug",
    "Grug125Adapter",
    "GlBridge",
    "GrugContainer",
    "VanillaNames",
    "GrugItem",
    # The screen Test.close_screen displays on 1.2.5 so the headless focus check does not open the
    # in-game menu over a screenshot. Same reason as GrugScreen: GUI glue a headless CI cannot
    # measure beyond the run that uses it.
    "GrugEmptyScreen",
    # Uploads grug textures into the two atlases 1.2.5 stitches at startup. Not excluded because the
    # GL work is out of reach: CI does run it, and coverage/tests/hot_reload_texture-Test.grug passes
    # here precisely because it succeeds. It is excluded for the reason given above this whole block,
    # that 1.2.5 contributes no exec data to measure it with.
    "GrugTextures",
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


def jacoco_report(
    cli: Path,
    execs: list[Path],
    classes: Path,
    sources: list[Path],
    csv_path: Path,
    xml_path: Path,
) -> None:
    command = ["java", "-jar", str(cli), "report", *map(str, execs)]
    command += ["--classfiles", str(classes)]
    for source in sources:
        if source.is_dir():
            command += ["--sourcefiles", str(source)]
    command += ["--csv", str(csv_path), "--xml", str(xml_path)]
    subprocess.run(command, check=True)


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


def move_initializer_lines(
    cls: ET.Element,
    method: ET.Element,
    gained: dict[str, int],
    package: ET.Element,
    everything_package: ET.Element,
) -> None:
    """Move a rescued <clinit>'s <line> entries to what the everything report shows them as.

    The method's first line and the number of lines it gained name the lines it is expected to
    span, and only a line the counted report has fully missed and the everything report has
    covered is moved, so coverage another method left on one of those lines is never counted.
    """
    line_count = gained.get("LINE", 0)
    source_name = cls.get("sourcefilename")
    if not line_count or not source_name or not method.get("line"):
        return
    counted_source = next(
        (source for source in package.findall("sourcefile") if source.get("name") == source_name),
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
        return
    counted_lines = {line.get("nr"): line for line in counted_source.findall("line")}
    everything_lines = {line.get("nr"): line for line in everything_source.findall("line")}
    start = int(method.get("line"))
    for number in range(start, start + line_count):
        counted_line = counted_lines.get(str(number))
        everything_line = everything_lines.get(str(number))
        if counted_line is None or everything_line is None:
            continue
        if int(counted_line.get("ci", "0")) or not int(everything_line.get("ci", "0")):
            continue
        for attribute in ("mi", "ci", "mb", "cb"):
            counted_line.set(attribute, everything_line.get(attribute, "0"))


def correct_xml(
    counted_xml: Path,
    everything_xml: Path,
    rescued: dict[tuple[str, str], dict[str, int]],
) -> None:
    """Move the rescued counters in the counted XML too, so the uploaded report agrees with the CSV.

    The <clinit> method's counters and lines move, and every ancestor's counters move by the same
    amounts, so the report stays internally consistent: a run whose initializer only another mod's
    test ran no longer uploads a report that says it was missed (#897).
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
            source_name = cls.get("sourcefilename")
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
            move_initializer_lines(cls, method, gained, package, everything_package)
        for kind, amount in package_gained.items():
            move_counter(package, kind, amount)
    for kind, amount in report_gained.items():
        move_counter(root, kind, amount)
    tree.write(counted_xml, encoding="utf-8", xml_declaration=True)


def rescue_class_initializers(csv_path: Path, counted_xml: Path, everything_xml: Path) -> None:
    """Count a class initializer that ran in a dropped segment as covered, in csv_path and the XML.

    csv_path and counted_xml are the report of the segments the gate counts, and everything_xml the
    report of those and the other mods' segments. A <clinit> that the second shows covered and the
    first missed ran only in another mod's segment, which is where a class's initializer runs when
    another mod's test is the first to use the class, and no test of mods/coverage/ can make it run
    again. Only the <clinit> of a class is moved; whatever else such a segment reached stays
    uncounted. The XML moves with the CSV, because it is the per-line report a failure is read from.
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
        correct_xml(counted_xml, everything_xml, rescued)


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
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    runners = discover_runners(args.artifacts)
    if CORE not in runners:
        print("error: no coverage-exec-core artifact found", file=sys.stderr)
        return 1

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
        csv_path = args.output / f"{runner}.csv"
        jacoco_report(
            args.jacoco_cli,
            execs,
            classes,
            source_dirs(runner),
            csv_path,
            args.output / f"{runner}.xml",
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
            rescue_class_initializers(csv_path, args.output / f"{runner}.xml", everything_xml)
        csv_paths.append(csv_path)
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
