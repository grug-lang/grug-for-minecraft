#!/usr/bin/env python3
"""Choose what a run of build.yml selects: the mods each loader job runs, and which jobs run (#909).

    main-selection.py <event> <base> [--repo <dir>] [--coverage-only] [--everything]

prints the discover job's outputs, one ``key=value`` line each, ready for ``$GITHUB_OUTPUT``:

    build=[{"dir": "1.2.5-forge", "mods": "coverage"}, ...]
    installed=[{"dir": "1.2.5-forge", "mods": "buildcraft,coverage"}]
    mods=buildcraft,coverage
    smoke=false
    reference=false

``build`` is the matrix of the development loader jobs, ``installed`` the matrix of the jobs that run
the suite again in a player's install, and ``mods`` the selection those jobs run, empty for "every
mod". ``smoke`` and ``reference`` say whether those two jobs can see anything the diff changed. The
reasons go to stderr, so the discover job's log says why the run got the selection it did.

Three rules, from #707's design (#909):

1. The mod axis is affected-mods.py's answer, the same one a pull request gets (#310, #628): the
   mods the diff touches, plus everything that names them, plus ``coverage``. A diff that leaves
   mods/ runs every mod.
2. The loader axis is affected-loaders.py's answer (#637): the loaders the diff reaches plus one
   representative per loader family, or the loaders a selected mod declares (#910). A change under
   ``.github/workflows/`` or ``.github/actions/`` runs every loader once instead, because no
   representative covers a job condition or an action: a broken one only shows on the jobs it edits.
3. A workflow_dispatch and the weekly schedule (#909's third rule) select nothing away: every loader
   runs every mod, which is the full pass #310 asks for and the run no selection can shrink.

A run that compares a head tree with a base commit (a push to main and a pull request) asks the
first two; an empty base, a base it cannot read, an empty diff and any error in this script answer
with everything, the direction that runs more, and the full run's loader list comes from the head
tree or, if git cannot read that, from the checkout's own ``loaders/``. A tree that names no loader
at all fails the step rather than print an empty matrix, which would skip every job and report a
green run that tested nothing.

What a selected loader runs is the mod axis, and what a loader the axes leave out runs is only
``mods/coverage``: combine-coverage.py builds each loader's report from that loader's own exec data
and classes, and the gate is 100%, so a loader job narrowed to the coverage mod is what keeps every
loader's adapter counted (#909). Its cost is flat in the number of mods rather than growing with the
catalogue: #707 measured 95 to 149 s a job, and this branch's own dispatch measured 77 to 148 s,
against a suite that gains about 16 s per port per loader. A pull request narrows its matrix to the
selected loaders and nothing else (#637: the 20 job cap), and a push runs every loader's job, because
main is the run that has to count every loader; the installed suite is the suite again, so it runs on
the selected loaders with the same mods.

The smoke games and the reference job are asked separately, because they observe other things: the
smoke games boot the universal jar on games grug has no layer for, which no file inside a mod can
reach, and reference-needed.py answers for the reference job's own harness and runtime.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from functools import lru_cache
from pathlib import Path, PurePosixPath

HERE = Path(__file__).resolve().parent
MODS = "mods"
# A change to the CI's own machinery. A job condition, a matrix or an action decides every job, and no
# family's representative is the one that runs it, so such a diff runs every loader once.
PIPELINE = (".github/workflows/", ".github/actions/")
# The events that build a head tree from a base commit, and so can narrow what they run. Every other
# event a dispatch or the schedule can deliver is the full run.
DIFFED = ("push", "pull_request")


@lru_cache(maxsize=None)
def sibling(name: str):
    """One of the scripts beside this one, so every script reads a diff and a tree the same way."""
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), HERE / f"{name}.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def every_mod_outputs(loaders: list[str]) -> dict:
    """The outputs of a run that selects nothing away: every loader with every mod."""
    return {
        "build": [{"dir": loader, "mods": ""} for loader in loaders],
        "installed": [{"dir": loader, "mods": ""} for loader in loaders],
        "mods": "",
        "smoke": "true",
        "reference": "true",
    }


def everything(repo: Path, reason: str) -> tuple[dict, list[str]]:
    """The full run's outputs and the line that says why this run is one.

    The loaders come from the head tree, and a tree this cannot read comes from the checkout's own
    ``loaders/``, since a git that fails does not remove the directories the run builds. A repository
    with neither cannot name a loader at all, and ``main`` then fails the step: printing an empty
    matrix would make the run green with nothing in it, which is the one answer this script must
    never give.
    """
    added = sibling("added-loaders")
    try:
        loaders = sorted(added.tree_loaders(repo, "HEAD"))
    except Exception as e:  # noqa: BLE001 - the working tree is still the tree this run builds
        loaders = added.loader_dirs(repo)
        reason += f"; the head tree cannot be read ({e!r}), so the checkout's loaders/ decides"
    return every_mod_outputs(loaders), [reason]


def inside_a_mod(path: str) -> bool:
    """Whether a path belongs to a mod, which is the only place a smoke game cannot see it."""
    return len(PurePosixPath(path).parts) >= 3 and PurePosixPath(path).parts[0] == MODS


def mod_axis(repo: Path, base: str) -> tuple[list[str] | None, list[str]]:
    """affected-mods.py's answer, where None means every mod, and one line per reason."""
    affected = sibling("affected-mods")
    try:
        selection = affected.affected_mods(repo, base, "HEAD")
    except affected.SelectEverything as e:
        return None, [f"every mod's tests run: {e}"]
    except Exception as e:  # noqa: BLE001 - a selection that cannot be computed runs every mod
        return None, [f"every mod's tests run: the selection cannot be computed ({e!r})"]
    if selection is None:
        return None, ["every mod's tests run: the diff reaches every mod's tests"]
    return selection, [f"the tests of {', '.join(selection)} run"]


def loader_axis(repo: Path, base: str, loaders: list[str]) -> tuple[list[str], list[str]]:
    """affected-loaders.py's answer for the loaders that run the selection, and its reasons."""
    try:
        selected, lines = sibling("affected-loaders").select(repo, base, "HEAD")
    except Exception as e:  # noqa: BLE001 - a selection that cannot be computed runs every loader
        return loaders, [f"every loader runs: the selection cannot be computed ({e!r})"]
    selected = sorted(set(selected) & set(loaders))
    if not selected:
        # No loader could be named, which an empty matrix would turn into no job at all.
        return loaders, [*lines, "no loader could be selected, so every loader runs the selection"]
    return selected, lines


def coverage_mod(repo: Path) -> bool:
    """Whether the head tree holds the mod every run has to run, which a narrowed loader runs alone."""
    affected = sibling("affected-mods")
    try:
        return affected.ALWAYS in affected.tree_files(repo, "HEAD")
    except Exception:  # noqa: BLE001 - a tree this cannot read leaves no mod to narrow to
        return False


def coverage_only(repo: Path) -> tuple[dict, list[str]]:
    """#831's dispatch input: the development jobs measure the coverage gate and the rest runs whole.

    The gate counts each loader's own adapter lines from that loader's own exec data, so the
    coverage-only shape is what measures the gate with the whole mod set absent, and the dispatch's
    other jobs run as in a full run, which is what makes the measurement about the gate.
    """
    loaders = sorted(sibling("added-loaders").tree_loaders(repo, "HEAD"))
    if not coverage_mod(repo):
        return everything(
            repo,
            "the coverage-only input is set but the head tree holds no"
            f" {sibling('affected-mods').ALWAYS} mod, so every mod runs",
        )
    affected = sibling("affected-mods")
    outputs = every_mod_outputs(loaders)
    outputs["build"] = [{"dir": loader, "mods": affected.ALWAYS} for loader in loaders]
    return outputs, [
        f"the coverage-only input is set, so every development job runs {affected.ALWAYS}'s tests"
        " alone and every other job runs as in a full run"
    ]


def decide(repo: Path, event: str, base: str = ""):
    """The outputs of this run, and one line per reason for the discover job's log."""
    loaders = sorted(sibling("added-loaders").tree_loaders(repo, "HEAD"))
    if event not in DIFFED:
        return everything(repo, f"this is a {event} run, which runs every loader with every mod")
    if not base:
        return everything(
            repo, f"a {event} run with no base commit to compare with runs everything"
        )

    affected = sibling("affected-mods")
    reference = sibling("reference-needed")
    try:
        paths = affected.changed_paths(repo, base, "HEAD")
    except Exception as e:  # noqa: BLE001 - a diff that cannot be read runs everything
        return everything(
            repo, f"the diff against {base} cannot be read ({e!r}), so everything runs"
        )
    if not paths:
        return everything(repo, "the diff is empty, which is more likely a mistake than a no-op")

    # Rule 1: the mod axis, which is affected-mods.py's own answer.
    selection, lines = mod_axis(repo, base)
    selection_text = "" if selection is None else ",".join(selection)

    # Rule 2: the loader axis, which is affected-loaders.py's, plus the CI's own machinery.
    selected, loader_lines = loader_axis(repo, base, loaders)
    lines.extend(loader_lines)
    pipeline = sorted({path for path in paths if path.startswith(PIPELINE)})
    if pipeline:
        selected = loaders
        lines.append(
            f"{', '.join(pipeline[:3])}{' and more' if len(pipeline) > 3 else ''} is the CI's own"
            " machinery, so every loader runs it once: no representative covers a job condition"
        )

    # The mods a loader the axes leave out runs: only the gate's own mod, so its adapter's lines stay
    # in the report the gate reads. A head tree without that mod has nothing to narrow to, so such a
    # loader runs the selection as well, which runs more.
    gate_mods = affected.ALWAYS if coverage_mod(repo) else selection_text

    chosen = set(selected)
    build = [
        {"dir": loader, "mods": selection_text if loader in chosen else gate_mods}
        for loader in loaders
    ]
    installed = [{"dir": loader, "mods": selection_text} for loader in loaders if loader in chosen]

    if event == "pull_request":
        # A pull request runs only the loaders it selected (#637: the plan allows 20 concurrent jobs
        # shared by the whole organisation), so its matrix is the selection and not every loader.
        build = installed

    # The smoke games boot the universal jar on games grug has no layer for, so a mod's own files
    # cannot change what they see. A file directly in mods/ belongs to no mod, so it is not inside
    # one and runs them.
    smoke = any(not inside_a_mod(path) for path in paths)
    lines.append(
        "the smoke games run: the diff holds a path outside every mod"
        if smoke
        else "the smoke games can be skipped: every changed path is inside a mod"
    )

    try:
        needed, reference_reason = sibling("reference-needed").needed(
            repo,
            base,
            "HEAD",
            reference.REFERENCE_MOD,
            reference.REFERENCE_LOADER,
        )
    except Exception as e:  # noqa: BLE001 - whatever went wrong, the reference job runs
        needed, reference_reason = True, f"the answer cannot be computed ({e!r}), so it runs"
    lines.append(f"the reference job {'runs' if needed else 'can be skipped'}: {reference_reason}")

    outputs = {
        "build": build,
        "installed": installed,
        "mods": selection_text,
        "smoke": "true" if smoke else "false",
        "reference": "true" if needed else "false",
    }
    return outputs, lines


def print_outputs(outputs: dict) -> None:
    for key, value in outputs.items():
        print(f"{key}={json.dumps(value) if isinstance(value, list) else value}")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("event", nargs="?", default="", help="github.event_name")
    parser.add_argument("base", nargs="?", default="", help="the commit the run started from")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    parser.add_argument(
        "--coverage-only",
        action="store_true",
        help="run only the coverage mod's tests in the development jobs, a dispatch's input (#831)",
    )
    parser.add_argument(
        "--everything",
        action="store_true",
        help="print the full run's outputs without reading a diff, the fallback a failed read uses",
    )
    args = parser.parse_args(argv)

    repo = Path(args.repo)
    try:
        if args.everything:
            outputs, lines = everything(repo, "the run asks for everything")
        elif args.coverage_only:
            outputs, lines = coverage_only(repo)
        else:
            outputs, lines = decide(repo, args.event, args.base)
    except Exception as e:  # noqa: BLE001 - whatever went wrong, every loader runs with every mod
        try:
            outputs, lines = everything(repo, f"{type(e).__name__}: {e}, so everything runs")
        except Exception as missing:  # noqa: BLE001 - no loader can be named, so the step fails
            # An empty build matrix is not "everything": the build job's `build != '[]'` condition
            # would skip it and coverage with it, and the run would go green with no test at all.
            # Failing the step is the only honest answer left, and the workflow's fallback runs the
            # script again before it gives up (#920 review).
            print(
                f"main-selection: {type(e).__name__}: {e}, and {type(missing).__name__}:"
                f" {missing}: no loader can be named from this tree, so this step fails rather than"
                " report a run with nothing in it",
                file=sys.stderr,
            )
            return 1

    for line in lines:
        print(f"main-selection: {line}", file=sys.stderr)
    summary = ", ".join(
        f"{entry['dir']}: {entry['mods'] or 'every mod'}" for entry in outputs["build"]
    )
    print(f"main-selection: the development jobs run {summary or 'no loader'}", file=sys.stderr)
    print_outputs(outputs)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
