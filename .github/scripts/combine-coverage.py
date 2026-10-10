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
"""

from __future__ import annotations

import argparse
import csv
import shutil
import subprocess
import sys
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
    "TitleScreenMixin",
    "ResourcePackManagerMixin",
    "CraftingManagerMixin",
    "BlockRendererMixin",
    "BlockRenderManagerMixin",
    "GrugMixin",
    "LiquidBlockRendererMixin",
    "LightTextureMixin",
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
            runners.setdefault(runner, {})["exec"] = sorted(entry.glob("*.exec"))
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
    args = parser.parse_args()

    args.output.mkdir(parents=True, exist_ok=True)
    runners = discover_runners(args.artifacts)
    if CORE not in runners:
        print("error: no coverage-exec-core artifact found", file=sys.stderr)
        return 1

    all_execs = [exec_file for info in runners.values() for exec_file in info.get("exec", [])]
    csv_paths: list[Path] = []
    for runner, info in sorted(runners.items()):
        execs = all_execs if runner == CORE else info.get("exec", [])
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
        csv_paths.append(csv_path)
        print(f"reported {runner}")

    combined = args.output / "combined.csv"
    sum_csvs(csv_paths, combined)
    missed, _covered, branch_missed, _branch_covered = print_totals(combined)
    if args.require_100 and (missed or branch_missed):
        print(
            "error: coverage is not 100% ("
            + str(missed)
            + " instructions and "
            + str(branch_missed)
            + " branches missed)",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
